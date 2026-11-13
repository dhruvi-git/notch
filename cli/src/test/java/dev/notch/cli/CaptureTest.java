package dev.notch.cli;

import com.sun.net.httpserver.HttpServer;
import dev.notch.guard.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Automatic capture end to end: real git repositories, synthetic Claude Code transcripts, a fake Notch API. */
class CaptureTest {

    @TempDir
    Path tmp;
    Path repo;
    Path home;
    Path claudeDir;
    HttpServer server;
    String api;
    final List<String[]> requests = new ArrayList<>();  // {method, path, body}
    final List<Map<String, Object>> projects = new ArrayList<>();
    ByteArrayOutputStream out;
    ByteArrayOutputStream err;

    static final String PROJECT = "11111111-2222-3333-4444-555555555555";
    static final String STALE = "99999999-8888-7777-6666-555555555555";

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectories(tmp.resolve("billing-service"));
        home = Files.createDirectories(tmp.resolve("home"));
        claudeDir = Files.createDirectories(tmp.resolve("claude"));
        Git.run(repo, "init", "-q");
        Git.run(repo, "config", "user.email", "t@example.com");
        Git.run(repo, "config", "user.name", "t");
        commit("src/db.ts", "/** Postgres pool. */\nexport const pool = 1;\n", "Use Postgres for billing data");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            synchronized (requests) {
                requests.add(new String[] {ex.getRequestMethod(), path + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()), body});
            }
            String reply = switch (ex.getRequestMethod() + " " + path) {
                case "GET /api/projects" -> Json.write(projects);
                case "POST /api/projects" -> {
                    projects.add(Map.of("id", PROJECT, "name", "billing-service"));
                    yield Json.write(Map.of("id", PROJECT, "name", "billing-service"));
                }
                case "GET /api/projects/" + PROJECT -> Json.write(Map.of("id", PROJECT, "name", "billing-service"));
                case "GET /api/projects/" + STALE -> "{\"code\":\"not_found\"}";
                case "GET /api/projects/" + PROJECT + "/memories" -> "[{\"id\":\"d1\",\"kind\":\"DECISION\",\"status\":\"ACTIVE\","
                        + "\"title\":\"Let's go with Postgres instead of MongoDB.\",\"rejectedOptions\":[\"MongoDB\"],\"createdAt\":\"2026-10-05T10:00:00Z\"}]";
                case "GET /api/projects/" + PROJECT + "/resume" -> "{\"whereYouStopped\":[\"Last session: Fix the pool (yesterday)\","
                        + "\"You asked: why does the pool run out?\"],\"openTasks\":[{\"title\":\"Add CSRF protection\"}],"
                        + "\"fixes\":[{\"title\":\"Fix: ECONNREFUSED 127.0.0.1:5432\"}],\"driftAlerts\":1}";
                case "GET /api/projects/" + PROJECT + "/search" -> "{\"hits\":[{\"text\":\"Error: connect ECONNREFUSED 127.0.0.1:5432. "
                        + "Start Postgres with docker compose up -d.\",\"decision\":false,\"source\":{\"conversationTitle\":\"Local Postgres\","
                        + "\"sentAt\":\"2026-09-01T10:00:00Z\"}}]}";
                default -> path.endsWith("/captures") || path.endsWith("/code-maps") ? "{\"id\":\"x\"}" : "{}";
            };
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            int status = path.equals("/api/projects/" + STALE) ? 404 : ex.getRequestMethod().equals("POST") ? 201 : 200;
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        api = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void commit(String path, String content, String message) throws Exception {
        Files.createDirectories(repo.resolve(path).getParent());
        Files.writeString(repo.resolve(path), content);
        Git.run(repo, "add", path);
        Git.run(repo, "commit", "-q", "-m", message);
    }

    /** A Claude Code transcript, in the shape Claude Code writes it. */
    private Path transcript(String session, String cwd) throws Exception {
        Path dir = Files.createDirectories(claudeDir.resolve("projects").resolve(cwd.replaceAll("[^A-Za-z0-9]", "-")));
        String c = Json.write(cwd);
        List<String> lines = List.of(
                "{\"type\":\"summary\",\"summary\":\"Pool work\"}",
                "{\"type\":\"user\",\"sessionId\":\"" + session + "\",\"uuid\":\"u1\",\"cwd\":" + c + ",\"timestamp\":\"2026-11-10T09:00:00Z\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"Why does the pool keep running out?<system-reminder>ignore me</system-reminder>\"}}",
                "{\"type\":\"assistant\",\"sessionId\":\"" + session + "\",\"uuid\":\"a1\",\"cwd\":" + c + ",\"timestamp\":\"2026-11-10T09:00:05Z\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"createInvoice holds a client for the whole transaction.\"},"
                        + "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"npm test\"}},"
                        + "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"src/db.ts\"}}]}}",
                "{\"type\":\"user\",\"sessionId\":\"" + session + "\",\"uuid\":\"u2\",\"cwd\":" + c + ",\"timestamp\":\"2026-11-10T09:00:09Z\","
                        + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"is_error\":true,\"content\":\"Error: timeout exceeded when trying to connect\"}]}}",
                "{\"type\":\"assistant\",\"sessionId\":\"" + session + "\",\"uuid\":\"a2\",\"cwd\":" + c + ",\"isSidechain\":true,\"timestamp\":\"2026-11-10T09:00:10Z\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"(a sub-agent's own chatter)\"}]}}",
                "{\"type\":\"assistant\",\"sessionId\":\"" + session + "\",\"uuid\":\"a3\",\"cwd\":" + c + ",\"timestamp\":\"2026-11-10T09:01:00Z\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Raised max to 20 and release the client in finally.\"},"
                        + "{\"type\":\"tool_use\",\"name\":\"Edit\",\"input\":{\"file_path\":\"src/db.ts\"}}]}}",
                "not json at all");
        Path f = dir.resolve(session + ".jsonl");
        Files.write(f, lines);
        return f;
    }

    private int run(String stdin, String... args) throws Exception {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        Main m = new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                Map.of("NOTCH_TOKEN", "ntch_pat_test", "NOTCH_API_URL", api, "CLAUDE_CONFIG_DIR", claudeDir.toString()), repo, home);
        return m.run(args, new BufferedReader(new StringReader(stdin)));
    }

    private List<Map<String, Object>> captures(String source) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (requests) {
            for (String[] r : requests) {
                if (r[1].endsWith("/captures")) {
                    Map<String, Object> body = Json.obj(Json.parse(r[2]));
                    if (source.equals(body.get("source"))) out.add(body);
                }
            }
        }
        return out;
    }

    @Test
    void transcriptsKeepWhatExplainsTheWork() throws Exception {
        ClaudeCodeSessions.Session s = ClaudeCodeSessions.read(transcript("s1", repo.toString()));
        assertEquals("s1", s.id());
        assertEquals("Pool work", s.title(), "Claude Code's own short title for the session wins");
        assertEquals("Why does the pool keep running out?", s.messages().get(0).text(), "the system reminder is stripped");
        List<String> texts = s.messages().stream().map(ClaudeCodeSessions.Message::text).toList();
        assertEquals(4, texts.size(), texts.toString());
        assertTrue(texts.get(1).contains("Ran: npm test") && !texts.get(1).contains("Read"), "commands kept, browsing dropped");
        assertEquals("note", s.messages().get(2).role(), "a failed command's output is kept as a note");
        assertTrue(texts.get(2).startsWith("Error: timeout exceeded"));
        assertTrue(texts.stream().noneMatch(t -> t.contains("sub-agent")), "side agents' chatter is skipped");
        assertTrue(texts.get(3).contains("Edited src/db.ts"));
    }

    @Test
    void sessionsAreMatchedToTheirRepositoryIncludingSubfolders() throws Exception {
        transcript("in-root", repo.toString());
        transcript("in-sub", repo.resolve("web").toString());
        transcript("elsewhere", tmp.resolve("other-project").toString());
        List<Path> files = ClaudeCodeSessions.filesFor(claudeDir.resolve("projects"), repo);
        assertEquals(2, files.size(), files.toString());
        assertEquals(ClaudeCodeSessions.normalise("C:\\Users\\Dell\\notch\\"), ClaudeCodeSessions.normalise("c:/users/dell/notch"),
                "Windows paths compare as Claude Code records them");
    }

    @Test
    void initSetsEverythingUpAndBackfillsHistory() throws Exception {
        transcript("s1", repo.toString());
        assertEquals(Main.OK, run("", "init"));
        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("Created the Notch project \u201cbilling-service\u201d"), said);
        assertTrue(Files.readString(repo.resolve(".notch")).contains(PROJECT));
        assertTrue(Files.readString(repo.resolve(".git/hooks/pre-commit")).contains("notch check --staged"));
        assertTrue(Files.readString(repo.resolve(".git/hooks/post-commit")).contains("sync --quiet"));
        Map<String, Object> local = Json.obj(Json.parse(Files.readString(repo.resolve(".claude/settings.local.json"))));
        assertTrue(Json.write(local.get("permissions")).contains("mcp__notch"), "Notch's own tools are pre-approved");
        assertFalse(said.contains("notch hook claude"), "no tip to run what init just ran");
        Map<String, Object> hooks = Json.obj(local.get("hooks"));
        assertTrue(Json.write(hooks.get("SessionStart")).contains("resume --claude-hook"));
        assertTrue(Json.write(hooks.get("SessionEnd")).contains("capture --claude-hook"));
        assertTrue(Json.write(hooks.get("PreToolUse")).contains("check --claude-hook"));
        assertTrue(Files.exists(repo.resolve(".mcp.json")));

        assertEquals(1, captures("claude-code").size(), "past sessions are backfilled");
        Map<String, Object> commits = captures("git").get(0);
        String text = Json.write(commits);
        assertTrue(text.contains("Use Postgres for billing data") && text.contains("Changed: src/db.ts"), text);
        assertTrue(requests.stream().anyMatch(r -> r[1].endsWith("/code-maps")), "the code map is uploaded");
        assertTrue(said.contains("Captured 1 session (4 messages), 1 commit and the code map."), said);
    }

    @Test
    void syncOnlySendsWhatsNew() throws Exception {
        Config.saveRepo(repo, PROJECT);
        transcript("s1", repo.toString());
        assertEquals(Main.OK, run("", "sync"));
        long before = requests.stream().filter(r -> r[0].equals("POST")).count();
        assertEquals(Main.OK, run("", "sync"));
        assertEquals(before, requests.stream().filter(r -> r[0].equals("POST")).count(), "nothing new, nothing sent: " + out);
        commit("src/pool.ts", "export const max = 20;\n", "Raise the pool size to 20");
        assertEquals(Main.OK, run("", "sync", "--no-map"));
        List<Map<String, Object>> git = captures("git");
        String last = Json.write(git.get(git.size() - 1));
        assertTrue(last.contains("Raise the pool size") && !last.contains("Use Postgres for billing data"), "only the new commit: " + last);
    }

    @Test
    void theSessionEndHookCapturesTheSessionThatFinished() throws Exception {
        Config.saveRepo(repo, PROJECT);
        Path t = transcript("s9", repo.toString());
        String event = Json.write(Map.of("hook_event_name", "SessionEnd", "transcript_path", t.toString(), "cwd", repo.toString()));
        assertEquals(Main.OK, run(event, "capture", "--claude-hook"));
        assertEquals(1, captures("claude-code").size());
        assertTrue(Json.write(captures("claude-code").get(0)).contains("claude-code:s9"));
        assertTrue(out.toString(StandardCharsets.UTF_8).isBlank(), "hooks stay quiet");
    }

    @Test
    void theSessionStartHookBriefsClaudeCode() throws Exception {
        Config.saveRepo(repo, PROJECT);
        Files.writeString(repo.resolve("src/db.ts"), "export const pool = 2;\n");  // an uncommitted change
        assertEquals(Main.OK, run("{}", "resume", "--claude-hook"));
        Map<String, Object> specific = Json.obj(Json.obj(Json.parse(out.toString(StandardCharsets.UTF_8))).get("hookSpecificOutput"));
        assertEquals("SessionStart", specific.get("hookEventName"));
        String context = (String) specific.get("additionalContext");
        assertTrue(context.contains("Where you stopped in billing-service"), context);
        assertTrue(context.contains("Uncommitted changes (1): src/db.ts"), context);
        assertTrue(context.contains("Last commit:"), context);
        assertTrue(context.contains("Add CSRF protection"), context);
        assertTrue(context.contains("Let's go with Postgres instead of MongoDB. (ruled out: MongoDB)"), context);
        assertTrue(context.contains("1 drift alert"), context);
    }

    @Test
    void hooksNeverFailWhenNotchCantRun() throws Exception {  // not set up: no project linked
        assertEquals(Main.OK, run("{}", "resume", "--claude-hook"));
        assertTrue(out.toString(StandardCharsets.UTF_8).isBlank());
        assertEquals(Main.OK, run("{\"transcript_path\":\"/nope.jsonl\"}", "capture", "--claude-hook"));
        assertEquals(Main.OK, run("", "sync", "--quiet"));
    }

    @Test
    void whySearchesEveryProject() throws Exception {
        projects.add(Map.of("id", PROJECT, "name", "billing-service"));
        assertEquals(Main.OK, run("", "why", "ECONNREFUSED", "127.0.0.1:5432"));
        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.startsWith("billing-service:"), said);
        assertTrue(said.contains("Local Postgres (2026-09-01)") && said.contains("docker compose up -d"), said);
        assertTrue(requests.stream().anyMatch(r -> r[1].contains("q=ECONNREFUSED+127.0.0.1%3A5432")));
    }

    @Test
    void editorTagsAreStrippedAndClaudeCodesOwnTitleWins() throws Exception {
        Path dir = Files.createDirectories(claudeDir.resolve("projects").resolve("x"));
        String c = Json.write(repo.toString());
        Path f = dir.resolve("s7.jsonl");
        Files.write(f, List.of(
                "{\"type\":\"user\",\"sessionId\":\"s7\",\"uuid\":\"u1\",\"cwd\":" + c + ",\"message\":{\"role\":\"user\",\"content\":"
                        + "\"<ide_opened_file>The user opened the file C:\\\\x\\\\.env.local in the IDE.</ide_opened_file> okay, let me explain the brief\"}}",
                "{\"type\":\"ai-title\",\"sessionId\":\"s7\",\"title\":\"Worli decision site UI improvements\"}"));
        ClaudeCodeSessions.Session s = ClaudeCodeSessions.read(f);
        assertEquals("Worli decision site UI improvements", s.title());
        assertEquals("okay, let me explain the brief", s.messages().get(0).text());
    }

    @Test
    void whyOnlyShowsResultsThatReallyMatch() {
        assertTrue(WhyMatch.relevant("ECONNREFUSED 127.0.0.1:5432", "Error: connect ECONNREFUSED 127.0.0.1:5432"));
        assertFalse(WhyMatch.relevant("useWorkspace must be used inside WorkspaceProvider",
                "The assignment brief: build a meeting room page that must be used by the family."), "shared common words don't count");
        assertTrue(WhyMatch.relevant("useWorkspace must be used inside WorkspaceProvider", "Wrap the app in WorkspaceProvider first."));
        assertTrue(WhyMatch.relevant("why is the pool running out", "the connection pool keeps running out under load"));
        assertFalse(WhyMatch.relevant("why is the pool running out", "we chose CSS modules for styling"));
    }

    @Test
    void anOlderInstallsSharedHooksMoveToLocalSettings() throws Exception {
        Files.createDirectories(repo.resolve(".claude"));
        Files.writeString(repo.resolve(".claude/settings.json"), "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Edit\",\"hooks\":"
                + "[{\"type\":\"command\",\"command\":\"notch check --claude-hook\"}]}]},\"model\":\"x\"}");
        assertEquals(Main.OK, run("", "hook", "claude"));
        Map<String, Object> shared = Json.obj(Json.parse(Files.readString(repo.resolve(".claude/settings.json"))));
        assertFalse(shared.containsKey("hooks"), "Notch's hooks leave the shared file");
        assertEquals("x", shared.get("model"), "other settings stay");
        assertTrue(Files.readString(repo.resolve(".claude/settings.local.json")).contains("check --claude-hook"));
    }

    @Test
    void initRelinksWhenTheLinkedProjectIsGone() throws Exception {
        Config.saveRepo(repo, STALE);  // e.g. after the database was reset
        assertEquals(Main.OK, run("", "init"));
        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("no longer exists on this Notch; linking again"), said);
        assertTrue(Files.readString(repo.resolve(".notch")).contains(PROJECT), "linked to the new project");
        assertFalse(captures("git").isEmpty(), "and the history was captured into it");
        Config.saveRepo(repo, STALE);
        assertEquals(Main.ERROR, run("", "sync"), "sync says so instead of capturing nothing");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Run notch init"));
    }

    @Test
    void commitTrailersAreLeftOut() {
        GitHistory.Commit c = new GitHistory.Commit("abcdef1234", null, "Tone down the animations",
                "Entrance animations felt fluttery.\n\nClaude-Session: https://claude.ai/code/session_x\nCo-Authored-By: Claude <noreply@anthropic.com>",
                List.of("components/ui/Animated.tsx"));
        String text = c.text();
        assertTrue(text.contains("Entrance animations felt fluttery."), text);
        assertFalse(text.contains("Claude-Session") || text.contains("Co-Authored-By"), text);
    }
}
