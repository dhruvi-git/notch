package dev.notch.cli;

import dev.notch.guard.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads Claude Code's own session transcripts (JSON lines under ~/.claude/projects) for one
 * repository. Messages keep what explains the work: what was asked, what was answered, the
 * commands run and files edited (one line each), and the output of commands that failed,
 * because that's where fixes come from. Tolerant of format details: unknown lines are skipped.
 */
final class ClaudeCodeSessions {

    record Message(String id, String role, String text, Instant at) {
    }

    record Session(String id, Path file, String title, Instant startedAt, List<Message> messages) {
    }

    static final int MAX_MESSAGE_CHARS = 8_000;
    static final int MAX_ERROR_CHARS = 1_200;
    /** Text editors and Claude Code add these around what the person typed; they aren't the message. */
    private static final Pattern NOISE = Pattern.compile(
            "(?s)<system-reminder>.*?</system-reminder>|<(ide_[a-z_]+|command-[a-z-]+|local-command-[a-z-]+)>.*?</\\1>");

    private ClaudeCodeSessions() {
    }

    /** Where Claude Code keeps transcripts: CLAUDE_CONFIG_DIR if set, else ~/.claude. */
    static Path projectsDir(Map<String, String> env, Path home) {
        String dir = env.get("CLAUDE_CONFIG_DIR");
        return (dir != null && !dir.isBlank() ? Path.of(dir) : home.resolve(".claude")).resolve("projects");
    }

    /** Transcript files whose sessions ran in {@code root} or a folder inside it. */
    static List<Path> filesFor(Path projectsDir, Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(projectsDir)) return out;
        String want = normalise(root.toString());
        try (Stream<Path> dirs = Files.list(projectsDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                        String cwd = cwdOf(f);
                        if (cwd != null && (normalise(cwd).equals(want) || normalise(cwd).startsWith(want + "/"))) out.add(f);
                    }
                }
            }
        }
        out.sort(null);
        return out;
    }

    /** Paths compared as Claude Code and Windows see them: forward slashes, no trailing slash, case-insensitive. */
    static String normalise(String path) {
        String p = path.replace('\\', '/');
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.toLowerCase(Locale.ROOT);
    }

    private static String cwdOf(Path f) {
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            for (int i = 0; i < 40 && (line = r.readLine()) != null; i++) {
                try {
                    Object cwd = Json.obj(Json.parse(line)).get("cwd");
                    if (cwd instanceof String s && !s.isBlank()) return s;
                } catch (RuntimeException notJson) {
                    // skip
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    static Session read(Path file) throws IOException {
        List<Message> messages = new ArrayList<>();
        String sessionId = null;
        String title = null;
        String namedTitle = null;  // Claude Code's own title for the session, when it wrote one
        Instant started = null;
        int n = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                n++;
                Map<String, Object> o;
                try {
                    o = Json.obj(Json.parse(line));
                } catch (RuntimeException notJson) {
                    continue;
                }
                if (sessionId == null && o.get("sessionId") instanceof String s) sessionId = s;
                String type = Json.str(o, "type");
                String named = namedTitle(type, o);
                if (named != null) namedTitle = named;
                if (!"user".equals(type) && !"assistant".equals(type)) continue;
                if (Boolean.TRUE.equals(o.get("isSidechain")) || Boolean.TRUE.equals(o.get("isMeta"))) continue;
                if (!(o.get("message") instanceof Map<?, ?> msg)) continue;
                Instant at = instant(Json.str(o, "timestamp"));
                Message m = message(type, Json.obj(msg).get("content"), Json.str(o, "uuid"), at, file, n);
                if (m == null) continue;
                if (started == null && at != null) started = at;
                if (title == null && "user".equals(m.role())) title = titleFrom(m.text());
                messages.add(m);
            }
        }
        String id = sessionId != null ? sessionId : file.getFileName().toString().replaceAll("\\.jsonl$", "");
        String best = namedTitle != null ? namedTitle : title != null ? title : "Claude Code session";
        return new Session(id, file, best, started, messages);
    }

    /** A title Claude Code generated for the session (an "ai-title" or summary line), if this is one. */
    static String namedTitle(String type, Map<String, Object> o) {
        if (type == null) return null;
        String t = type.toLowerCase(Locale.ROOT);
        if (!t.contains("title") && !t.equals("summary")) return null;
        for (String key : new String[] {"title", "aiTitle", "ai_title", "summary"}) {
            if (o.get(key) instanceof String s && !s.isBlank()) return titleFrom(s);
        }
        return null;
    }

    private static Message message(String type, Object content, String uuid, Instant at, Path file, int line) {
        StringBuilder text = new StringBuilder();
        StringBuilder errors = new StringBuilder();
        if (content instanceof String s) {
            text.append(s);
        } else if (content instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (!(b instanceof Map<?, ?> raw)) continue;
                Map<String, Object> block = Json.obj(raw);
                switch (String.valueOf(block.get("type"))) {
                    case "text" -> append(text, Json.str(block, "text"));
                    case "tool_use" -> append(text, toolLine(Json.str(block, "name"), block.get("input")));
                    case "tool_result" -> {
                        if (Boolean.TRUE.equals(block.get("is_error"))) {
                            String output = clip(resultText(block.get("content")).strip(), MAX_ERROR_CHARS);
                            // Most failures already say "Error: ..."; don't say it twice.
                            append(errors, output.regionMatches(true, 0, "error", 0, 5) ? output : "Error: " + output);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        String clean = NOISE.matcher(text).replaceAll("").strip();
        if (clean.startsWith("<command-") || clean.startsWith("<local-command") || clean.startsWith("Caveat: The messages below")) clean = "";
        String role = type;
        if (clean.isEmpty() && !errors.isEmpty()) {
            clean = errors.toString().strip();
            role = "note";  // command output, not something the person typed
        } else if (!errors.isEmpty()) {
            clean = clean + "\n" + errors.toString().strip();
        }
        if (clean.isBlank()) return null;
        String id = uuid != null ? uuid : file.getFileName() + ":" + line;
        return new Message(id, role, clip(clean, MAX_MESSAGE_CHARS), at);
    }

    /** One line per tool call: what was run or changed, which is what someone coming back needs. */
    static String toolLine(String name, Object input) {
        Map<String, Object> in = input instanceof Map<?, ?> m ? Json.obj(m) : Map.of();
        if (name == null) return "";
        return switch (name) {
            case "Bash", "PowerShell" -> "Ran: " + clip(String.valueOf(in.getOrDefault("command", "")), 300);
            case "Edit", "MultiEdit", "Write", "NotebookEdit" -> "Edited " + in.getOrDefault("file_path", in.getOrDefault("notebook_path", "a file"));
            case "Read", "Glob", "Grep", "LS", "TodoWrite", "WebFetch", "WebSearch" -> "";  // looking around, not doing
            default -> "Used " + name;
        };
    }

    private static String resultText(Object content) {
        if (content instanceof String s) return s;
        StringBuilder sb = new StringBuilder();
        if (content instanceof List<?> parts) {
            for (Object p : parts) if (p instanceof Map<?, ?> m && m.get("text") != null) sb.append(m.get("text")).append('\n');
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null || s.isBlank()) return;
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(s);
    }

    static String titleFrom(String text) {
        String first = text.strip().split("\\R", 2)[0].strip();
        return first.length() <= 90 ? first : first.substring(0, 89).strip() + "\u2026";
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }

    private static Instant instant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
