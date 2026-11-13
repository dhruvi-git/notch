package dev.notch.cli;

import dev.notch.guard.Json;
import dev.notch.explain.CodeMap;
import dev.notch.explain.CodeMapBuilder;
import dev.notch.explain.CodeMapJson;
import dev.notch.explain.SourceFile;
import dev.notch.guard.Decision;
import dev.notch.guard.DiffGuard;
import dev.notch.guard.DiffParser;
import dev.notch.guard.FileChange;
import dev.notch.guard.GuardFinding;
import dev.notch.guard.InstallCommands;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code notch}: the guard and MCP server for coding agents.
 *
 * <pre>
 * notch login --token &lt;token&gt; [--api &lt;url&gt;]   save your personal token
 * notch link &lt;project-id&gt;                      link this repository to a project
 * notch status                                 show what's configured
 * notch check [--staged | --base &lt;ref&gt; | --diff &lt;file|-&gt;] [--format text|github|json]
 * notch hook git|claude|github                 install a guard
 * notch setup claude|cursor|codex              connect a coding agent over MCP
 * notch mcp                                    run the MCP server (agents start this)
 * </pre>
 *
 * Exit codes for {@code check}: 0 nothing blocking, 1 a HIGH finding (or any finding with
 * {@code --strict}), 3 the check couldn't run. Hooks fail open: if Notch can't run, they
 * never block your work. In Claude Code hook mode a block is reported as a JSON "deny"
 * decision on stdout with exit 0, which stops file edits and terminal commands alike.
 */
public final class Main {

    static final int OK = 0, FOUND = 1, ERROR = 3;

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> env;
    private final Path cwd;
    private final Path home;

    Main(PrintStream out, PrintStream err, Map<String, String> env, Path cwd, Path home) {
        this.out = out;
        this.err = err;
        this.env = env;
        this.cwd = cwd;
        this.home = home;
    }

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        Main m = new Main(out, err, System.getenv(), Path.of("").toAbsolutePath(), Path.of(System.getProperty("user.home")));
        System.exit(m.run(args, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))));
    }

    int run(String[] args, BufferedReader stdin) throws IOException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            out.print(HELP);
            return OK;
        }
        Args a = new Args(args);
        Path root = Git.root(cwd);
        Config config = Config.load(env, home, root);
        if (a.flag("project") != null) config = config.withProject(a.flag("project"));
        return switch (args[0]) {
            case "version", "--version" -> {
                out.println("notch " + McpServer.VERSION);
                yield OK;
            }
            case "login" -> login(a, config);
            case "link" -> link(a, root);
            case "status" -> status(config, root);
            case "check" -> check(a, config, root, stdin);
            case "hook" -> hook(a, root);
            case "setup" -> setup(a, root);
            case "map" -> map(a, config, root);
            case "init" -> init(a, config, root);
            case "sync" -> sync(a, config, root);
            case "capture" -> capture(a, config, root, stdin);
            case "resume" -> resume(a, config, root, stdin);
            case "why" -> why(a, config);
            case "mcp" -> {
                err.println("notch: MCP server ready on stdio" + (config.project == null ? " (no project linked yet)" : ""));
                new McpServer(new ApiClient(config, false), out, err).serve(stdin);
                yield OK;
            }
            default -> {
                err.println("Unknown command: " + args[0] + ". Run notch help.");
                yield ERROR;
            }
        };
    }

    // ------------------------------------------------------------------ setup commands

    private int login(Args a, Config config) throws IOException {
        String token = a.flag("token");
        if (token == null || !token.startsWith("ntch_")) {
            err.println("Usage: notch login --token <token> [--api <url>]. Create a token in Notch under Settings.");
            return ERROR;
        }
        config.saveUser(a.flag("api"), token);
        Config fresh = Config.load(Map.of(), home, null);
        try {
            String who = new ApiClient(fresh, false).whoAmI();
            out.println("Signed in as " + who + " at " + fresh.api + ".");
        } catch (NotchBackend.NotchException e) {
            out.println("Saved the token, but couldn't confirm it: " + e.getMessage());
        }
        return OK;
    }

    private int link(Args a, Path root) throws IOException {
        if (root == null) {
            err.println("Run notch link inside a git repository.");
            return ERROR;
        }
        String project = a.positional(1);
        if (project == null || !project.matches("[0-9a-fA-F-]{36}")) {
            err.println("Usage: notch link <project-id>. The id is in the project's address in Notch: /p/<project-id>/...");
            return ERROR;
        }
        Config.saveRepo(root, project);
        out.println("Linked " + root.getFileName() + " to project " + project + ". The .notch file is safe to commit.");
        return OK;
    }

    private int status(Config config, Path root) {
        out.println("API:        " + config.api);
        out.println("Signed in:  " + (config.token == null ? "no (run notch login)" : "yes, token " + config.token.substring(0, Math.min(13, config.token.length())) + "…"));
        out.println("Repository: " + (root == null ? "not in a git repository" : root));
        out.println("Project:    " + (config.project == null ? "not linked (run notch link <project-id>)" : config.project));
        if (config.token != null && config.project != null) {
            ApiClient api = new ApiClient(config, false);
            try {
                out.println("Name:       " + api.projectName());
                out.println("Decisions:  " + api.decisions().size() + " in force");
            } catch (NotchBackend.NotchException e) {
                out.println("Connection: " + e.getMessage());
            }
        }
        return OK;
    }

    // ------------------------------------------------------------------ check

    private int check(Args a, Config config, Path root, BufferedReader stdin) throws IOException {
        boolean claude = a.has("claude-hook");
        boolean failOpen = a.has("fail-open") || claude;
        String format = a.flagOr("format", "text");
        List<FileChange> changes;
        try {
            changes = claude ? fromClaudeHook(stdin, root) : readChanges(a, root, stdin);
        } catch (IOException | IllegalArgumentException e) {
            return failed("Couldn't read the changes: " + e.getMessage(), failOpen);
        }
        if (changes.isEmpty()) {
            if (format.equals("json")) out.println("[]");
            return OK;
        }
        ApiClient api = new ApiClient(config, a.has("offline"));
        List<Decision> decisions;
        try {
            decisions = api.decisions();
        } catch (NotchBackend.NotchException e) {
            return failed(e.getMessage(), failOpen);
        }
        if (api.warning() != null) err.println("notch: " + api.warning());
        List<GuardFinding> findings = new DiffGuard().check(decisions, changes);
        boolean blocking = findings.stream().anyMatch(f -> f.severity() == GuardFinding.Severity.HIGH)
                || (a.has("strict") && !findings.isEmpty());

        if (claude) {
            if (findings.isEmpty()) return OK;
            out.println(denyDecision(forAgent(findings)));  // Claude Code cancels the call and shows Claude why
            return OK;
        }
        switch (format) {
            case "json" -> out.println(Json.write(findings.stream().map(Main::toJson).toList()));
            case "github" -> findings.forEach(f -> out.println(githubAnnotation(f)));
            default -> printText(findings, blocking, a.has("staged") || (!a.has("base") && !a.has("diff")));
        }
        return blocking ? FOUND : OK;
    }

    private List<FileChange> readChanges(Args a, Path root, BufferedReader stdin) throws IOException {
        String diff;
        if (a.flag("diff") != null) {
            diff = a.flag("diff").equals("-") ? readAll(stdin) : Files.readString(cwd.resolve(a.flag("diff")), StandardCharsets.UTF_8);
        } else {
            if (root == null) throw new IllegalArgumentException("not in a git repository");
            diff = a.flag("base") != null ? Git.diffSince(root, a.flag("base")) : Git.stagedDiff(root);
        }
        return DiffParser.parse(diff);
    }

    /** The PreToolUse "deny" decision: Claude Code cancels the tool call and shows Claude the reason. */
    static String denyDecision(String reason) {
        Map<String, Object> specific = new LinkedHashMap<>();
        specific.put("hookEventName", "PreToolUse");
        specific.put("permissionDecision", "deny");
        specific.put("permissionDecisionReason", reason);
        return Json.write(Map.of("hookSpecificOutput", specific));
    }

    /**
     * Claude Code's PreToolUse input: the new content of an Edit, MultiEdit or Write, or the
     * packages a terminal command would install (Bash, or PowerShell on Windows).
     */
    static List<FileChange> fromClaudeHook(BufferedReader stdin, Path root) throws IOException {
        Map<String, Object> event = Json.obj(Json.parse(readAll(stdin)));
        if (!(event.get("tool_input") instanceof Map<?, ?>)) return List.of();
        Map<String, Object> input = Json.obj(event.get("tool_input"));
        if (input.get("command") instanceof String command) return InstallCommands.parse(command);  // Bash, PowerShell
        String path = Json.str(input, "file_path");
        if (path == null) return List.of();
        if (root != null) {
            Path p = Path.of(path);
            if (p.isAbsolute() && p.startsWith(root)) path = root.relativize(p).toString();
        }
        path = path.replace('\\', '/');  // as git writes paths, on Windows too
        List<String> texts = new ArrayList<>();
        if (input.get("content") != null) texts.add(Json.str(input, "content"));
        if (input.get("new_string") != null) texts.add(Json.str(input, "new_string"));
        if (input.get("edits") instanceof List<?> edits) {
            for (Object e : edits) if (e instanceof Map<?, ?> m && m.get("new_string") != null) texts.add(m.get("new_string").toString());
        }
        boolean wholeFile = input.get("content") != null;
        List<FileChange> out = new ArrayList<>();
        for (String t : texts) out.add(DiffParser.fromContent(path, t, wholeFile));
        return out;
    }

    static String forAgent(List<GuardFinding> findings) {
        StringBuilder sb = new StringBuilder("Notch blocked this: it goes against decisions recorded for this project.\n");
        for (GuardFinding f : findings) sb.append("- ").append(f.location()).append(": ").append(f.message()).append('\n');
        sb.append("Don't work around this or try a variation. Tell the user which decision this conflicts with and ask whether "
                + "they want to keep the decision or change it. If they confirm a change, replace the decision with "
                + "notch_replace_decision (or in Notch), then continue.");
        return sb.toString();
    }

    private void printText(List<GuardFinding> findings, boolean blocking, boolean commit) {
        if (findings.isEmpty()) {
            out.println("notch: no conflicts with the project's decisions.");
            return;
        }
        out.println();
        out.println("Notch: " + findings.size() + (findings.size() == 1 ? " change goes" : " changes go") + " against the project's decisions");
        out.println();
        for (GuardFinding f : findings) {
            out.printf("  %-6s %s%n", f.severity() == GuardFinding.Severity.HIGH ? "HIGH" : "MEDIUM", f.location());
            out.println("         " + f.lineText());
            out.println("         " + f.message());
            out.println();
        }
        if (blocking && commit) {
            out.println("Commit stopped. If the decision has changed, replace it in Notch first.");
            out.println("To commit anyway: git commit --no-verify");
        } else if (!blocking) {
            out.println("Nothing here is blocking; worth a look before you push.");
        }
    }

    static String githubAnnotation(GuardFinding f) {
        String level = f.severity() == GuardFinding.Severity.HIGH ? "error" : "warning";
        String props = "file=" + escapeProp(f.path()) + (f.line() > 0 ? ",line=" + f.line() : "")
                + ",title=" + escapeProp("Notch: goes against a decision");
        return "::" + level + " " + props + "::" + escapeData(f.message());
    }

    private static String escapeData(String s) {
        return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    private static String escapeProp(String s) {
        return escapeData(s).replace(":", "%3A").replace(",", "%2C");
    }

    private static Map<String, Object> toJson(GuardFinding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", f.severity().name());
        m.put("kind", f.kind().name());
        m.put("path", f.path());
        m.put("line", f.line());
        m.put("tech", f.tech());
        m.put("decisionId", f.decisionId());
        m.put("decision", f.decisionTitle());
        m.put("message", f.message());
        return m;
    }

    private int failed(String message, boolean failOpen) {
        err.println("notch: " + message);
        if (failOpen) {
            err.println("notch: skipping the check; nothing was blocked.");
            return OK;
        }
        return ERROR;
    }

    // ------------------------------------------------------------------ capture and recall

    /**
     * One-time setup in a repository: links (or creates) the project, installs every hook, and
     * captures existing history. After this, Notch keeps itself up to date.
     */
    private int init(Args a, Config config, Path root) throws IOException {
        if (root == null) {
            err.println("Run notch init inside a git repository.");
            return ERROR;
        }
        if (config.token == null) {
            err.println("First sign in: create a personal token in Notch (Settings), then run notch login --token <token>.");
            return ERROR;
        }
        String name = root.getFileName().toString();
        if (config.project != null) {
            try {
                if (!new ApiClient(config, false).projectExists(config.project)) {
                    out.println("The project in .notch (" + config.project + ") no longer exists on this Notch; linking again.");
                    config = config.withProject(null);
                }
            } catch (NotchBackend.NotchException e) {
                err.println("notch: " + e.getMessage());
                return ERROR;
            }
        }
        if (config.project == null) {
            try {
                ApiClient api = new ApiClient(config, false);
                String id = null;
                for (Map<String, Object> p : api.projects()) if (name.equalsIgnoreCase(Json.str(p, "name"))) id = Json.str(p, "id");
                if (id == null) {
                    id = api.createProject(name);
                    out.println("Created the Notch project \u201c" + name + "\u201d.");
                } else {
                    out.println("Linked to your existing Notch project \u201c" + name + "\u201d.");
                }
                Config.saveRepo(root, id);
                config = config.withProject(id);
            } catch (NotchBackend.NotchException e) {
                err.println("notch: " + e.getMessage());
                return ERROR;
            }
        }
        gitHook(root, a.has("force"));
        postCommitHook(root);
        claudeHook(root);
        claudeSessionHooks(root);
        setup(new Args(new String[] {"setup", "claude", "--quiet-tip"}), root);
        out.println();
        out.println("Capturing your history: Claude Code sessions in this repository, recent commits and the code map\u2026");
        try {
            Sync.Result r = new Sync(new ApiClient(config, false), config, root, env, home, err).run(true);
            out.printf("Captured %d session%s (%,d messages), %d commit%s%s.%n", r.sessions(), r.sessions() == 1 ? "" : "s",
                    r.sessionMessages(), r.commits(), r.commits() == 1 ? "" : "s", r.mapped() ? " and the code map" : "");
        } catch (NotchBackend.NotchException | IOException e) {
            err.println("notch: the first capture didn't finish (" + e.getMessage() + "). It will retry after your next commit.");
        }
        out.println();
        out.println("Done. From now on Notch updates itself after every commit and every Claude Code session.");
        out.println("Coming back later? Run notch resume, or just open Claude Code here: it starts with a briefing.");
        return OK;
    }

    private int sync(Args a, Config config, Path root) throws IOException {
        boolean quiet = a.has("quiet");
        if (root == null || config.token == null || config.project == null) {
            if (!quiet) err.println("Run notch init in this repository first.");
            return quiet ? OK : ERROR;
        }
        try {
            ApiClient syncApi = new ApiClient(config, false);
            if (!quiet && !syncApi.projectExists(config.project)) {
                err.println("The project in .notch no longer exists on this Notch. Run notch init to link this repository again.");
                return ERROR;
            }
            Sync.Result r = new Sync(syncApi, config, root, env, home, err).run(!a.has("no-map"));
            if (!quiet) {
                out.printf("Captured %d new session%s (%,d messages) and %d commit%s%s.%n", r.sessions(), r.sessions() == 1 ? "" : "s",
                        r.sessionMessages(), r.commits(), r.commits() == 1 ? "" : "s", r.mapped() ? ", and refreshed the code map" : "");
            }
            return OK;
        } catch (NotchBackend.NotchException | IOException | RuntimeException e) {
            if (!quiet) err.println("notch: " + e.getMessage());
            return quiet ? OK : ERROR;  // hooks never fail because of Notch
        }
    }

    /** Claude Code's SessionEnd hook: capture the session that just finished. Never fails the hook. */
    private int capture(Args a, Config config, Path root, BufferedReader stdin) {
        try {
            Path transcript = null;
            if (a.has("claude-hook")) {
                Map<String, Object> event = Json.obj(Json.parse(readAll(stdin)));
                String path = Json.str(event, "transcript_path");
                if (path != null) transcript = Path.of(path);
                String cwd = Json.str(event, "cwd");
                if (root == null && cwd != null) root = Git.root(Path.of(cwd));
                if (root != null && config.project == null) config = Config.load(env, home, root);
            } else if (a.positional(1) != null) {
                transcript = cwd.resolve(a.positional(1));
            }
            if (transcript == null || !Files.isRegularFile(transcript) || root == null || config.project == null || config.token == null) return OK;
            int n = new Sync(new ApiClient(config, false), config, root, env, home, err).captureOne(transcript);
            if (!a.has("claude-hook")) out.println("Captured " + n + " messages.");
        } catch (Exception e) {
            if (!a.has("claude-hook")) err.println("notch: " + e.getMessage());
        }
        return OK;
    }

    /** The briefing; with --claude-hook, handed to Claude Code as context at session start. */
    private int resume(Args a, Config config, Path root, BufferedReader stdin) {
        boolean hook = a.has("claude-hook");
        if (config.token == null || config.project == null) {
            if (!hook) err.println("Run notch init in this repository first.");
            return hook ? OK : ERROR;
        }
        try {
            ApiClient api = new ApiClient(config, false);
            String name = root != null ? root.getFileName().toString() : api.projectName();
            String text = Briefing.build(api, root, name);
            if (hook) {
                Map<String, Object> specific = new LinkedHashMap<>();
                specific.put("hookEventName", "SessionStart");
                specific.put("additionalContext", "Notch briefing for this project:\n" + text);
                out.println(Json.write(Map.of("hookSpecificOutput", specific)));
            } else {
                out.println(text);
            }
            return OK;
        } catch (NotchBackend.NotchException | RuntimeException e) {
            if (!hook) err.println("notch: " + e.getMessage());
            return hook ? OK : ERROR;
        }
    }

    /** "Have I seen this before?" across every project: errors, fixes and decisions. */
    private int why(Args a, Config config) {
        String query = String.join(" ", a.positionalsFrom(1)).strip();
        if (query.isEmpty()) {
            err.println("Usage: notch why \"<error message or question>\"");
            return ERROR;
        }
        if (config.token == null) {
            err.println("Sign in first: notch login --token <token>");
            return ERROR;
        }
        try {
            ApiClient api = new ApiClient(config, false);
            List<Map<String, Object>> projects = api.projects();
            // The current project first, then the rest.
            projects.sort((x, y) -> Boolean.compare(!String.valueOf(x.get("id")).equals(config.project),
                    !String.valueOf(y.get("id")).equals(config.project)));
            int shown = 0;
            for (Map<String, Object> p : projects.subList(0, Math.min(12, projects.size()))) {
                Map<String, Object> r = api.searchIn(Json.str(p, "id"), query, 6);
                List<Object> hits = new ArrayList<>();
                for (Object h : Json.arr(r.get("hits"))) {
                    if (WhyMatch.relevant(query, Json.str(Json.obj(h), "text")) && hits.size() < 3) hits.add(h);
                }
                if (hits.isEmpty()) continue;
                out.println(Json.str(p, "name") + ":");
                for (Object h : hits) {
                    Map<String, Object> hit = Json.obj(h);
                    Map<String, Object> src = Json.obj(hit.get("source"));
                    String text = Json.str(hit, "text").replaceAll("\\s+", " ").strip();
                    out.println("  " + Json.str(src, "conversationTitle") + " (" + String.valueOf(Json.str(src, "sentAt")).substring(0, 10) + ")"
                            + (Boolean.TRUE.equals(hit.get("decision")) ? ", led to a decision" : ""));
                    out.println("    " + (text.length() > 240 ? text.substring(0, 239) + "\u2026" : text));
                    shown++;
                }
                out.println();
            }
            if (shown == 0) out.println("Not seen before: nothing in any of your projects mentions that.");
            return OK;
        } catch (NotchBackend.NotchException e) {
            err.println("notch: " + e.getMessage());
            return ERROR;
        }
    }

    // ------------------------------------------------------------------ map

    /**
     * Maps the codebase on this machine and uploads only the map: paths, imports, names and
     * summaries. The code itself never leaves the computer.
     */
    private int map(Args a, Config config, Path root) throws IOException {
        if (root == null) {
            err.println("Run notch map inside a git repository.");
            return ERROR;
        }
        long start = System.nanoTime();
        List<SourceFile> files = new ArrayList<>();
        for (String rel : Git.projectFiles(root)) {
            Path p = root.resolve(rel);
            try {
                if (!Files.isRegularFile(p) || Files.size(p) > CodeMapBuilder.MAX_FILE_BYTES) continue;
                files.add(new SourceFile(rel, Files.readString(p, StandardCharsets.UTF_8)));
            } catch (IOException | java.io.UncheckedIOException notText) {
                // binary or unreadable: not part of the map
            }
        }
        CodeMap m = new CodeMapBuilder().build(files);
        double seconds = (System.nanoTime() - start) / 1e9;
        CodeMap.Stats s = m.stats();
        out.printf("Mapped %,d files (%,d code files, %,d lines) in %.1f s.%n", s.files(), s.codeFiles(), s.lines(), seconds);
        if (!m.startHere().isEmpty()) {
            out.println("\nStart here, the code most of the rest depends on:");
            for (String p : m.startHere().subList(0, Math.min(5, m.startHere().size()))) {
                CodeMap.FileInfo f = m.file(p);
                out.printf("  %-56s used by %d%n", p, f.importedBy().size());
            }
        }
        out.printf("%nEntry points: %d.  Possibly unused: %d.%n", m.entryPoints().size(), m.possiblyUnused().size());
        if (!s.filesWithSecrets().isEmpty()) {
            out.println("\nThese files look like they hold secrets (the map records only their paths, never the values):");
            s.filesWithSecrets().forEach(p -> out.println("  " + p));
        }
        String json = CodeMapJson.write(m);
        if (a.flag("out") != null) {
            Files.writeString(cwd.resolve(a.flag("out")), json, StandardCharsets.UTF_8);
            out.println("\nWrote the map to " + a.flag("out") + ".");
        }
        if (a.has("dry-run") || a.flag("out") != null) return OK;
        String label = a.flag("label") != null ? a.flag("label") : root.getFileName().toString();
        try {
            new ApiClient(config, false).uploadMap(label, json);
        } catch (NotchBackend.NotchException e) {
            err.println("notch: " + e.getMessage());
            return ERROR;
        }
        out.println("\nUploaded the map to Notch as \u201c" + label + "\u201d. Only paths, imports, names and summaries were sent; no code.");
        out.println("Open Explain in the project to browse it.");
        return OK;
    }

    // ------------------------------------------------------------------ hooks and setup

    private int hook(Args a, Path root) throws IOException {
        if (root == null) {
            err.println("Run notch hook inside a git repository.");
            return ERROR;
        }
        String kind = a.positional(1);
        if ("git".equals(kind)) return gitHook(root, a.has("force"));
        if ("claude".equals(kind)) return claudeHook(root);
        if ("github".equals(kind)) return githubWorkflow(root);
        err.println("Usage: notch hook git|claude|github");
        return ERROR;
    }

    static final String HOOK_MARKER = "# Installed by Notch";

    private int gitHook(Path root, boolean force) throws IOException {
        Path hooks = Git.hooksDir(root);
        Files.createDirectories(hooks);
        Path hook = hooks.resolve("pre-commit");
        if (Files.exists(hook) && !Files.readString(hook).contains(HOOK_MARKER)) {
            if (!force) {
                err.println("A pre-commit hook already exists at " + hook + ". Add this line to it, or rerun with --force to replace it:");
                err.println("  notch check --staged --fail-open || exit 1");
                return ERROR;
            }
            Files.move(hook, hooks.resolve("pre-commit.before-notch"));
        }
        Files.writeString(hook, gitHookScript(ownJar()), StandardCharsets.UTF_8);
        makeExecutable(hook);
        out.println("Installed the pre-commit guard at " + root.relativize(hook) + ".");
        return OK;
    }

    /**
     * The pre-commit script. Commits from IDEs often run without your shell's PATH, so it tries
     * {@code notch} on PATH, then the jar this was installed from, and if neither is there it
     * skips the check with a warning instead of blocking the commit.
     */
    static String gitHookScript(Path jar) {
        String jarPath = jar == null ? "" : jar.toAbsolutePath().toString().replace('\\', '/');
        return "#!/bin/sh\n" + HOOK_MARKER + ": stops commits that go against the project's decisions.\n"
                + "# Skip once with: git commit --no-verify\n"
                + "if command -v notch >/dev/null 2>&1; then exec notch check --staged --fail-open; fi\n"
                + "JAR=\"" + jarPath + "\"\n"
                + "if [ -n \"$JAR\" ] && [ -f \"$JAR\" ] && command -v java >/dev/null 2>&1; then\n"
                + "  exec java -jar \"$JAR\" check --staged --fail-open\n"
                + "fi\n"
                + "echo \"notch: not found, so the decision check was skipped. Put cli/bin on your PATH.\" >&2\n"
                + "exit 0\n";
    }

    static final String SYNC_MARKER = "# Installed by Notch: sync";

    /** After each commit: capture it, new Claude Code sessions and the code map, in the background. */
    private void postCommitHook(Path root) throws IOException {
        Path hooks = Git.hooksDir(root);
        Files.createDirectories(hooks);
        Path hook = hooks.resolve("post-commit");
        if (Files.exists(hook) && !Files.readString(hook).contains(SYNC_MARKER)) {
            out.println("A post-commit hook already exists; add this line to it so Notch stays current: notch sync --quiet &");
            return;
        }
        Files.writeString(hook, postCommitScript(ownJar()), StandardCharsets.UTF_8);
        makeExecutable(hook);
        out.println("Installed the post-commit sync at " + root.relativize(hook) + ".");
    }

    static String postCommitScript(Path jar) {
        String jarPath = jar == null ? "" : jar.toAbsolutePath().toString().replace('\\', '/');
        return "#!/bin/sh\n" + SYNC_MARKER + ". Captures this commit, new Claude Code sessions and the code map\n"
                + "# in the background, so committing never waits for it.\n"
                + "if command -v notch >/dev/null 2>&1; then (notch sync --quiet >/dev/null 2>&1 &); exit 0; fi\n"
                + "JAR=\"" + jarPath + "\"\n"
                + "if [ -n \"$JAR\" ] && [ -f \"$JAR\" ] && command -v java >/dev/null 2>&1; then\n"
                + "  (java -jar \"$JAR\" sync --quiet >/dev/null 2>&1 &)\n"
                + "fi\n"
                + "exit 0\n";
    }

    /** Claude Code: a briefing when a session starts, and capture when it ends. */
    private void claudeSessionHooks(Path root) throws IOException {
        Path file = claudeLocalSettings(root);
        Map<String, Object> settings = readJsonObject(file);
        Map<String, Object> hooks = child(settings, "hooks");
        String launch = String.join(" ", launcher().stream().map(Main::shellQuote).toList());
        for (String[] spec : new String[][] {{"SessionStart", "resume --claude-hook"}, {"SessionEnd", "capture --claude-hook"}}) {
            List<Object> entries = hooks.get(spec[0]) instanceof List<?> l ? new ArrayList<>(Json.arr(l)) : new ArrayList<>();
            entries.removeIf(e -> Json.write(e).contains(spec[1]));
            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("type", "command");
            cmd.put("command", launch + " " + spec[1]);
            entries.add(Map.of("hooks", List.of(cmd)));
            hooks.put(spec[0], entries);
        }
        settings.put("hooks", hooks);
        // Let Claude Code use Notch's own tools without asking, including in headless sessions.
        Map<String, Object> permissions = child(settings, "permissions");
        List<Object> allow = permissions.get("allow") instanceof List<?> l ? new ArrayList<>(Json.arr(l)) : new ArrayList<>();
        if (!allow.contains("mcp__notch")) allow.add("mcp__notch");
        permissions.put("allow", allow);
        settings.put("permissions", permissions);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.pretty(settings), StandardCharsets.UTF_8);
        out.println("Claude Code will start each session with a Notch briefing and capture it when it ends.");
    }

    /** The jar this CLI is running from, or null when run from classes (tests, IDE). */
    static Path ownJar() {
        try {
            Path p = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return p.toString().endsWith(".jar") ? p.toAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** True when {@code notch} (or notch.cmd on Windows) is on the PATH right now. */
    private boolean notchOnPath() {
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : List.of("notch", "notch.cmd", "notch.bat")) {
                if (Files.isRegularFile(Path.of(dir, name))) return true;
            }
        }
        return false;
    }

    /**
     * How agent configs should start Notch. Claude Code started from an editor doesn't see a PATH
     * set in some terminal, so when Notch runs from its jar, configs name the Java executable and
     * the jar by full path. (From classes, as in tests, it's plain "notch".)
     */
    private List<String> launcher() {
        Path jar = ownJar();
        if (jar == null) return List.of("notch");
        String java = ProcessHandle.current().info().command().orElse("java").replace('\\', '/');
        return List.of(java, "-jar", jar.toString().replace('\\', '/'));
    }

    /**
     * Claude Code settings for this machine only: hooks there name this computer's Java and jar,
     * so they go in .claude/settings.local.json (personal, not shared) rather than settings.json.
     */
    static Path claudeLocalSettings(Path root) {
        return root.resolve(".claude").resolve("settings.local.json");
    }

    /** Removes Notch's hook commands from a Claude Code settings file (older installs used settings.json). */
    private static void removeNotchHooks(Path file) throws IOException {
        if (!Files.exists(file)) return;
        Map<String, Object> settings = readJsonObject(file);
        if (!(settings.get("hooks") instanceof Map<?, ?>)) return;
        Map<String, Object> hooks = child(settings, "hooks");
        boolean changed = false;
        for (String event : new ArrayList<>(hooks.keySet())) {
            if (!(hooks.get(event) instanceof List<?> l)) continue;
            List<Object> entries = new ArrayList<>(Json.arr(l));
            changed |= entries.removeIf(e -> Json.write(e).matches(".*(check|resume|capture) --claude-hook.*"));
            if (entries.isEmpty()) hooks.remove(event);
            else hooks.put(event, entries);
        }
        if (!changed) return;
        if (hooks.isEmpty()) settings.remove("hooks");
        else settings.put("hooks", hooks);
        Files.writeString(file, Json.pretty(settings), StandardCharsets.UTF_8);
    }

    private int claudeHook(Path root) throws IOException {
        removeNotchHooks(root.resolve(".claude").resolve("settings.json"));
        Path file = claudeLocalSettings(root);
        Map<String, Object> settings = readJsonObject(file);
        Map<String, Object> hooks = child(settings, "hooks");
        List<Object> pre = hooks.get("PreToolUse") instanceof List<?> l ? new ArrayList<>(Json.arr(l)) : new ArrayList<>();
        boolean replaced = pre.removeIf(e -> Json.write(e).contains("check --claude-hook"));  // older Notch entries
        List<String> cmd = new ArrayList<>(launcher());
        cmd.add("check");
        cmd.add("--claude-hook");
        Map<String, Object> hook = new LinkedHashMap<>();
        hook.put("type", "command");
        hook.put("command", String.join(" ", cmd.stream().map(Main::shellQuote).toList()));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("matcher", "Edit|MultiEdit|Write|Bash|PowerShell");
        entry.put("hooks", List.of(hook));
        pre.add(entry);
        hooks.put("PreToolUse", pre);
        settings.put("hooks", hooks);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.pretty(settings), StandardCharsets.UTF_8);
        out.println((replaced ? "Updated the Claude Code hook in " : "Added the Claude Code hook to ") + root.relativize(file)
                + ". File edits and package installs that go against a decision will be stopped, and Claude will be told why.");
        return OK;
    }

    private static String shellQuote(String s) {
        return s.matches("[A-Za-z0-9_./:=@-]+") ? s : "\"" + s.replace("\"", "\\\"") + "\"";
    }

    private int githubWorkflow(Path root) throws IOException {
        Path file = root.resolve(".github").resolve("workflows").resolve("notch.yml");
        if (Files.exists(file)) {
            out.println(root.relativize(file) + " already exists.");
            return OK;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, GITHUB_WORKFLOW, StandardCharsets.UTF_8);
        out.println("Wrote " + root.relativize(file) + ". In the repository's settings, add:");
        out.println("  secret   NOTCH_TOKEN     a personal token from Notch Settings");
        out.println("  variable NOTCH_API_URL   your Notch API address");
        out.println("  variable NOTCH_CLI_URL   where to download notch.jar");
        return OK;
    }

    private int setup(Args a, Path root) throws IOException {
        String agent = a.positional(1);
        if ("codex".equals(agent)) {
            out.println("Add this to ~/.codex/config.toml:");
            out.println();
            out.println("[mcp_servers.notch]");
            out.println("command = \"notch\"");
            out.println("args = [\"mcp\"]");
            return OK;
        }
        if (root == null) {
            err.println("Run notch setup inside a git repository.");
            return ERROR;
        }
        Path file;
        if ("claude".equals(agent)) file = root.resolve(".mcp.json");
        else if ("cursor".equals(agent)) file = root.resolve(".cursor").resolve("mcp.json");
        else {
            err.println("Usage: notch setup claude|cursor|codex");
            return ERROR;
        }
        Map<String, Object> config = readJsonObject(file);
        Map<String, Object> servers = child(config, "mcpServers");
        List<String> launch = launcher();
        List<String> args = new ArrayList<>(launch.subList(1, launch.size()));
        args.add("mcp");
        Map<String, Object> notch = new LinkedHashMap<>();
        notch.put("command", launch.get(0));
        notch.put("args", args);
        servers.put("notch", notch);
        config.put("mcpServers", servers);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, Json.pretty(config), StandardCharsets.UTF_8);
        out.println("Connected " + agent + " to Notch through " + root.relativize(file) + ".");
        if ("claude".equals(agent) && !a.has("quiet-tip")) {
            out.println("Also stop edits that break decisions: notch hook claude");
        }
        return OK;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> readJsonObject(Path file) throws IOException {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.isBlank()) return new LinkedHashMap<>();
        return new LinkedHashMap<>(Json.obj(Json.parse(text)));
    }

    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        return parent.get(key) instanceof Map<?, ?> m ? new LinkedHashMap<>(Json.obj(m)) : new LinkedHashMap<>();
    }

    private static void makeExecutable(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException | IOException e) {
            p.toFile().setExecutable(true);
        }
    }

    private static String readAll(BufferedReader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    /** Minimal argument parsing: positionals, --flag value, and boolean --switches. */
    static final class Args {
        private final List<String> positionals = new ArrayList<>();
        private final Map<String, String> flags = new LinkedHashMap<>();

        Args(String[] args) {
            for (int i = 0; i < args.length; i++) {
                String s = args[i];
                if (s.startsWith("--")) {
                    String name = s.substring(2);
                    int eq = name.indexOf('=');
                    if (eq >= 0) flags.put(name.substring(0, eq), name.substring(eq + 1));
                    else if (i + 1 < args.length && !args[i + 1].startsWith("--") && takesValue(name)) flags.put(name, args[++i]);
                    else flags.put(name, "true");
                } else {
                    positionals.add(s);
                }
            }
        }

        private static boolean takesValue(String name) {
            return List.of("token", "api", "project", "base", "diff", "format", "label", "out").contains(name);
        }

        String flag(String name) {
            String v = flags.get(name);
            return "true".equals(v) && takesValue(name) ? null : v;
        }

        String flagOr(String name, String fallback) {
            String v = flag(name);
            return v == null ? fallback : v;
        }

        boolean has(String name) {
            return flags.containsKey(name);
        }

        String positional(int i) {
            return i < positionals.size() ? positionals.get(i) : null;
        }

        List<String> positionalsFrom(int i) {
            return i < positionals.size() ? positionals.subList(i, positionals.size()) : List.of();
        }
    }

    static final String HELP = """
            notch: keeps code and coding agents in line with your project's decisions

            Start here
              notch init                                  set up this repository: hooks, agent briefing, history capture
              notch resume                                where you stopped: last session, uncommitted work, next tasks
              notch why "<error or question>"             have you seen this before? searches every project

            Setup
              notch login --token <token> [--api <url>]   save a personal token (Notch > Settings)
              notch link <project-id>                     link this repository to a Notch project
              notch status                                show the current setup

            Guard
              notch check                                 check staged changes (what the pre-commit hook runs)
              notch check --base origin/main              check this branch against main
              notch check --diff changes.patch            check a diff file, or - for stdin
                  --format text|github|json   --strict (MEDIUM blocks too)   --offline (use saved decisions)
              notch hook git                              stop commits that go against a decision
              notch hook claude                           stop Claude Code edits that go against a decision
              notch hook github                           check pull requests in GitHub Actions

            Capture (automatic after notch init)
              notch sync                                  capture new Claude Code sessions, commits and the code map
              notch capture <transcript.jsonl>            capture one Claude Code session

            Explain
              notch map                                   map this codebase locally and upload the map (no code is sent)
                  --dry-run (just show the summary)   --out map.json (save instead of uploading)   --label <name>

            Agents
              notch setup claude|cursor|codex             give an agent Notch's decisions and history over MCP
              notch mcp                                   the MCP server itself (agents start it)

            Exit codes for check: 0 fine, 1 blocking finding, 3 couldn't run.
            Hooks never block your work when Notch itself can't run.
            """;

    static final String GITHUB_WORKFLOW = """
            # Checks every pull request against the decisions recorded in Notch.
            name: Notch

            on:
              pull_request:

            jobs:
              decisions:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                    with:
                      fetch-depth: 0
                  - uses: actions/setup-java@v4
                    with:
                      distribution: temurin
                      java-version: "21"
                  - name: Download the Notch CLI
                    run: curl -fsSL "${{ vars.NOTCH_CLI_URL }}" -o notch.jar
                  - name: Check changes against decisions
                    env:
                      NOTCH_TOKEN: ${{ secrets.NOTCH_TOKEN }}
                      NOTCH_API_URL: ${{ vars.NOTCH_API_URL }}
                    run: java -jar notch.jar check --base "origin/${{ github.base_ref }}" --format github
            """;
}
