package dev.notch.cli;

import dev.notch.explain.CodeMap;
import dev.notch.explain.CodeMapBuilder;
import dev.notch.explain.CodeMapJson;
import dev.notch.explain.SourceFile;
import dev.notch.guard.Json;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures what's new in a repository since the last sync: Claude Code sessions, commits and the
 * code map. What was sent is remembered in a small state file, so each sync only sends new work,
 * and the server skips anything it already has.
 */
final class Sync {

    record Result(int sessions, int sessionMessages, int commits, boolean mapped) {
    }

    /** Requests stay well under the API's limits. */
    static final int MAX_BATCH_CONVERSATIONS = 10;
    static final int MAX_BATCH_CHARS = 2_500_000;

    private final ApiClient api;
    private final Config config;
    private final Path root;
    private final Map<String, String> env;
    private final Path home;
    private final PrintStream log;

    Sync(ApiClient api, Config config, Path root, Map<String, String> env, Path home, PrintStream log) {
        this.api = api;
        this.config = config;
        this.root = root;
        this.env = env;
        this.home = home;
        this.log = log;
    }

    Result run(boolean withMap) throws IOException, NotchBackend.NotchException {
        Map<String, Object> state = readState();
        Map<String, Object> seen = state.get("sessions") instanceof Map<?, ?> m ? new LinkedHashMap<>(Json.obj(m)) : new LinkedHashMap<>();

        // 1. Claude Code sessions changed since last time.
        List<ClaudeCodeSessions.Session> sessions = new ArrayList<>();
        for (Path f : ClaudeCodeSessions.filesFor(ClaudeCodeSessions.projectsDir(env, home), root)) {
            long modified = Files.getLastModifiedTime(f).toMillis();
            Object before = seen.get(f.toString());
            if (before instanceof Number n && n.longValue() >= modified) continue;
            ClaudeCodeSessions.Session s = ClaudeCodeSessions.read(f);
            if (!s.messages().isEmpty()) sessions.add(s);
            seen.put(f.toString(), modified);
        }
        int messages = uploadSessions(sessions);
        state.put("sessions", seen);
        writeState(state);  // sessions are safe even if a later step fails

        // 2. Commits since last time.
        String last = state.get("lastCommit") instanceof String s ? s : null;
        List<GitHistory.Commit> commits = GitHistory.read(root, last);
        if (!commits.isEmpty()) {
            List<Object> msgs = new ArrayList<>();
            for (GitHistory.Commit c : commits) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("externalId", c.hash());
                m.put("role", "note");
                m.put("text", c.text());
                if (c.at() != null) m.put("sentAt", c.at().toString());
                msgs.add(m);
            }
            Map<String, Object> conv = new LinkedHashMap<>();
            conv.put("externalId", "git:" + root.getFileName());
            conv.put("title", "Git history of " + root.getFileName());
            conv.put("messages", msgs);
            api.capture("git", root.getFileName() + " commits", List.of(conv));
        }
        String head = GitHistory.head(root);
        if (head != null) state.put("lastCommit", head);
        writeState(state);

        // 3. The code map, when the code changed.
        boolean mapped = false;
        if (withMap && head != null && !head.equals(state.get("mapHead"))) {
            CodeMap map = buildMap();
            if (!map.files().isEmpty()) {
                api.uploadMap(root.getFileName().toString(), CodeMapJson.write(map));
                state.put("mapHead", head);
                writeState(state);
                mapped = true;
            }
        }
        return new Result(sessions.size(), messages, commits.size(), mapped);
    }

    /** Captures one finished session right away (Claude Code's SessionEnd hook). */
    int captureOne(Path transcript) throws IOException, NotchBackend.NotchException {
        ClaudeCodeSessions.Session s = ClaudeCodeSessions.read(transcript);
        if (s.messages().isEmpty()) return 0;
        int n = uploadSessions(List.of(s));
        Map<String, Object> state = readState();
        Map<String, Object> seen = state.get("sessions") instanceof Map<?, ?> m ? new LinkedHashMap<>(Json.obj(m)) : new LinkedHashMap<>();
        seen.put(transcript.toString(), Files.getLastModifiedTime(transcript).toMillis());
        state.put("sessions", seen);
        writeState(state);
        return n;
    }

    /** Sends sessions in batches; a very long session is split across requests and joined on the server. */
    private int uploadSessions(List<ClaudeCodeSessions.Session> sessions) throws NotchBackend.NotchException {
        List<Object> batch = new ArrayList<>();
        long chars = 0;
        int sent = 0;
        for (ClaudeCodeSessions.Session s : sessions) {
            List<Object> part = new ArrayList<>();
            for (ClaudeCodeSessions.Message m : s.messages()) {
                if (chars > 0 && chars + m.text().length() > MAX_BATCH_CHARS) {
                    if (!part.isEmpty()) batch.add(conversation(s, part));
                    api.capture("claude-code", "Claude Code sessions", batch);
                    batch = new ArrayList<>();
                    part = new ArrayList<>();
                    chars = 0;
                }
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("externalId", m.id());
                msg.put("role", m.role());
                msg.put("text", m.text());
                if (m.at() != null) msg.put("sentAt", m.at().toString());
                part.add(msg);
                chars += m.text().length();
                sent++;
            }
            if (!part.isEmpty()) batch.add(conversation(s, part));
            if (batch.size() >= MAX_BATCH_CONVERSATIONS) {
                api.capture("claude-code", "Claude Code sessions", batch);
                batch = new ArrayList<>();
                chars = 0;
            }
        }
        if (!batch.isEmpty()) api.capture("claude-code", "Claude Code sessions", batch);
        return sent;
    }

    private static Map<String, Object> conversation(ClaudeCodeSessions.Session s, List<Object> messages) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("externalId", "claude-code:" + s.id());
        c.put("title", s.title());
        if (s.startedAt() != null) c.put("startedAt", s.startedAt().toString());
        c.put("messages", messages);
        return c;
    }

    CodeMap buildMap() throws IOException {
        List<SourceFile> files = new ArrayList<>();
        for (String rel : Git.projectFiles(root)) {
            Path p = root.resolve(rel);
            try {
                if (!Files.isRegularFile(p) || Files.size(p) > CodeMapBuilder.MAX_FILE_BYTES) continue;
                files.add(new SourceFile(rel, Files.readString(p, StandardCharsets.UTF_8)));
            } catch (IOException | java.io.UncheckedIOException notText) {
                // binary or unreadable
            }
        }
        return new CodeMapBuilder().build(files);
    }

    // ------------------------------------------------------------------ state

    private Path stateFile() {
        return config.cacheDir.resolve("sync-" + config.project + ".json");
    }

    private Map<String, Object> readState() {
        try {
            Path f = stateFile();
            if (Files.isRegularFile(f)) return new LinkedHashMap<>(Json.obj(Json.parse(Files.readString(f, StandardCharsets.UTF_8))));
        } catch (IOException | RuntimeException corrupt) {
            log.println("notch: starting a fresh sync record (" + corrupt.getMessage() + ")");
        }
        return new LinkedHashMap<>();
    }

    private void writeState(Map<String, Object> state) throws IOException {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), Json.pretty(state), StandardCharsets.UTF_8);
    }
}
