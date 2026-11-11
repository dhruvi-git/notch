package dev.notch.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the CLI against temporary git repositories, with decisions served from the offline cache. */
class MainTest {

    @TempDir
    Path tmp;
    Path repo;
    Path home;
    ByteArrayOutputStream out;
    ByteArrayOutputStream err;

    private static final String PROJECT = "11111111-2222-3333-4444-555555555555";
    private static final String DECISIONS = "[{\"id\":\"d1\",\"kind\":\"DECISION\",\"status\":\"ACTIVE\","
            + "\"title\":\"Let's go with Postgres instead of MongoDB because we need joins.\",\"rejectedOptions\":[\"MongoDB\"],"
            + "\"createdAt\":\"2026-10-05T10:00:00Z\"}]";

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectories(tmp.resolve("repo"));
        home = Files.createDirectories(tmp.resolve("home"));
        Git.run(repo, "init", "-q");
        Git.run(repo, "config", "user.email", "t@example.com");
        Git.run(repo, "config", "user.name", "t");
        Path cache = Files.createDirectories(home.resolve(".cache/notch"));
        Files.writeString(cache.resolve("decisions-" + PROJECT + ".json"), DECISIONS);
    }

    private int run(String stdin, String... args) throws Exception {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        Main m = new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                Map.of("NOTCH_TOKEN", "ntch_pat_test", "NOTCH_API_URL", "http://127.0.0.1:9"), repo, home);
        return m.run(args, new BufferedReader(new StringReader(stdin)));
    }

    private void stage(String path, String content) throws Exception {
        Files.createDirectories(repo.resolve(path).getParent());
        Files.writeString(repo.resolve(path), content);
        Git.run(repo, "add", path);
    }

    @Test
    void linkWritesTheRepoFile() throws Exception {
        assertEquals(Main.OK, run("", "link", PROJECT));
        assertTrue(Files.readString(repo.resolve(".notch")).contains("project=" + PROJECT));
        assertEquals(Main.ERROR, run("", "link", "not-an-id"));
    }

    @Test
    void stagedRuledOutDependencyBlocksTheCommit() throws Exception {
        run("", "link", PROJECT);
        stage("package.json", "{\n  \"dependencies\": {\n    \"mongoose\": \"^8.4.0\"\n  }\n}\n");
        assertEquals(Main.FOUND, run("", "check", "--staged", "--offline"));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("HIGH   package.json:3"), text);
        assertTrue(text.contains("Commit stopped"));
    }

    @Test
    void cleanChangesPass() throws Exception {
        run("", "link", PROJECT);
        stage("package.json", "{ \"dependencies\": { \"pg\": \"^8\" } }\n");
        assertEquals(Main.OK, run("", "check", "--offline"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("no conflicts"));
    }

    @Test
    void githubAndJsonFormats() throws Exception {
        run("", "link", PROJECT);
        stage("src/db.py", "from pymongo import MongoClient\n");
        run("", "check", "--offline", "--format", "github");
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("::error file=src/db.py,line=1,title=Notch%3A goes against a decision::Adds MongoDB (pymongo)"),
                out.toString(StandardCharsets.UTF_8));
        run("", "check", "--offline", "--format", "json");
        Map<String, Object> f = Json.obj(Json.arr(Json.parse(out.toString(StandardCharsets.UTF_8))).get(0));
        assertEquals("HIGH", f.get("severity"));
        assertEquals("d1", f.get("decisionId"));
    }

    private Map<String, Object> denial() {
        String text = out.toString(StandardCharsets.UTF_8).strip();
        assertFalse(text.isEmpty(), "expected a deny decision on stdout");
        return Json.obj(Json.obj(Json.parse(text)).get("hookSpecificOutput"));
    }

    @Test
    void claudeHookBlocksEditsAndExplains() throws Exception {
        run("", "link", PROJECT);
        String event = "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\""
                + repo.resolve("src/db.js").toString().replace("\\", "\\\\") + "\",\"content\":\"const mongoose = require('mongoose');\"}}";
        assertEquals(Main.OK, run(event, "check", "--claude-hook", "--offline"));
        Map<String, Object> d = denial();
        assertEquals("PreToolUse", d.get("hookEventName"));
        assertEquals("deny", d.get("permissionDecision"));
        String reason = (String) d.get("permissionDecisionReason");
        assertTrue(reason.contains("Notch blocked this"), reason);
        assertTrue(reason.contains("src/db.js:1"), reason);
        String edit = "{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"a.js\",\"old_string\":\"x\",\"new_string\":\"const pg = require('pg');\"}}";
        assertEquals(Main.OK, run(edit, "check", "--claude-hook", "--offline"));
        assertTrue(out.toString(StandardCharsets.UTF_8).isBlank(), "allowed edits print nothing");
    }

    @Test
    void claudeHookBlocksPackageInstallsInTheTerminal() throws Exception {
        run("", "link", PROJECT);
        String bash = "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"cd api && npm install mongoose@8\"}}";
        assertEquals(Main.OK, run(bash, "check", "--claude-hook", "--offline"));
        assertTrue(((String) denial().get("permissionDecisionReason")).contains("Adds MongoDB (mongoose)"));
        String powershell = "{\"tool_name\":\"PowerShell\",\"tool_input\":{\"command\":\"npm install mongoose\"}}";
        assertEquals(Main.OK, run(powershell, "check", "--claude-hook", "--offline"));
        assertEquals("deny", denial().get("permissionDecision"), "PowerShell is how Claude Code runs commands on Windows");
        String windowsPath = "{\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"src\\\\db.js\",\"content\":\"require('mongoose')\"}}";
        assertEquals(Main.OK, run(windowsPath, "check", "--claude-hook", "--offline"));
        assertTrue(((String) denial().get("permissionDecisionReason")).contains("src/db.js:1"), "paths use forward slashes");
        String harmless = "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"npm test\"}}";
        assertEquals(Main.OK, run(harmless, "check", "--claude-hook", "--offline"));
        assertTrue(out.toString(StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void gitHookSkipsInsteadOfBlockingWhenNotchIsMissing() throws Exception {
        Path script = tmp.resolve("pre-commit");
        Files.writeString(script, Main.gitHookScript(null));
        Process p = new ProcessBuilder("sh", script.toString()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), output);
        assertTrue(output.contains("decision check was skipped"), output);
    }

    @Test
    void hooksFailOpenWhenNotchCantRun() throws Exception {  // no project linked, no cache
        stage("package.json", "\"mongoose\": \"8\"\n");
        assertEquals(Main.OK, run("", "check", "--staged", "--fail-open", "--offline"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("nothing was blocked"));
        assertEquals(Main.ERROR, run("", "check", "--staged", "--offline"));
    }

    @Test
    void installsHooksAndAgentConfig() throws Exception {
        assertEquals(Main.OK, run("", "hook", "git"));
        Path hook = repo.resolve(".git/hooks/pre-commit");
        assertTrue(Files.readString(hook).contains("notch check --staged --fail-open"));
        assertTrue(Files.isExecutable(hook));

        Files.createDirectories(repo.resolve(".claude"));
        Files.writeString(repo.resolve(".claude/settings.json"), "{\"permissions\":{\"allow\":[\"Read\"]}}");
        assertEquals(Main.OK, run("", "hook", "claude"));
        assertEquals(Main.OK, run("", "hook", "claude"));  // idempotent
        Map<String, Object> settings = Json.obj(Json.parse(Files.readString(repo.resolve(".claude/settings.json"))));
        assertTrue(settings.containsKey("permissions"), "existing settings are kept");
        List<Object> pre = Json.arr(Json.obj(settings.get("hooks")).get("PreToolUse"));
        assertEquals(1, pre.size());
        assertEquals("Edit|MultiEdit|Write|Bash|PowerShell", Json.obj(pre.get(0)).get("matcher"));

        assertEquals(Main.OK, run("", "setup", "claude"));
        Map<String, Object> mcp = Json.obj(Json.obj(Json.parse(Files.readString(repo.resolve(".mcp.json")))).get("mcpServers"));
        assertEquals("notch", Json.obj(mcp.get("notch")).get("command"));
    }

    @Test
    void existingGitHookIsNotOverwrittenWithoutForce() throws Exception {
        Path hook = repo.resolve(".git/hooks/pre-commit");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\nnpm test\n");
        assertEquals(Main.ERROR, run("", "hook", "git"));
        assertEquals("#!/bin/sh\nnpm test\n", Files.readString(hook));
        assertEquals(Main.OK, run("", "hook", "git", "--force"));
        assertTrue(Files.exists(hook.resolveSibling("pre-commit.before-notch")));
    }
}
