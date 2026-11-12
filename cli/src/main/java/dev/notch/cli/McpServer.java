package dev.notch.cli;

import dev.notch.guard.Decision;
import dev.notch.guard.DecisionFacts;
import dev.notch.guard.TechCatalog;
import dev.notch.guard.DiffGuard;
import dev.notch.guard.DiffParser;
import dev.notch.guard.GuardFinding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Notch as an MCP server over stdio, so any coding agent (Claude Code, Codex, Cursor) can read
 * the project's decisions, search its history, check a change before writing it, and record
 * decisions as they're made.
 *
 * <p>Messages are newline-delimited JSON-RPC 2.0 on stdin and stdout. Stdout carries protocol
 * messages only; anything for people goes to stderr.
 */
public final class McpServer {

    static final String VERSION = "0.1.0";
    static final String INSTRUCTIONS = "Notch holds this project's decisions, open tasks and history. "
            + "Before choosing a library, database, auth method or architecture, call notch_decisions. "
            + "Before writing a dependency or import, call notch_check_change. "
            + "When the user settles a choice, call notch_record_decision with what was chosen, why, and what was ruled out.";

    private final NotchBackend backend;
    private final PrintStream out;
    private final PrintStream log;

    public McpServer(NotchBackend backend, PrintStream out, PrintStream log) {
        this.backend = backend;
        this.out = out;
        this.log = log;
    }

    public void serve(BufferedReader in) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            String reply = handle(line);
            if (reply != null) {
                out.print(reply);
                out.print('\n');  // MCP stdio messages end in \n; println would write \r\n on Windows
                out.flush();
            }
        }
    }

    /** One message in, at most one message out (notifications get no reply). */
    String handle(String message) {
        Map<String, Object> req;
        try {
            req = Json.obj(Json.parse(message));
        } catch (RuntimeException e) {
            return error(null, -32700, "Parse error: " + e.getMessage());
        }
        Object id = req.get("id");
        String method = Json.str(req, "method");
        if (method == null) return id == null ? null : error(id, -32600, "Invalid request: no method");
        Map<String, Object> params = req.get("params") instanceof Map<?, ?> ? Json.obj(req.get("params")) : Map.of();
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> call(params);
                default -> {
                    if (method.startsWith("notifications/")) yield null;
                    throw new NoSuchMethodException(method);
                }
            };
            if (id == null) return null;  // a notification
            return result == null ? null : ok(id, result);
        } catch (NoSuchMethodException e) {
            return id == null ? null : error(id, -32601, "Method not found: " + method);
        } catch (RuntimeException e) {
            log.println("notch mcp: " + e.getMessage());
            return id == null ? null : error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        Map<String, Object> r = new LinkedHashMap<>();
        // The tools used here are stable across protocol versions, so accept the client's.
        Object requested = params.get("protocolVersion");
        r.put("protocolVersion", requested != null ? requested : "2025-06-18");
        r.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        r.put("serverInfo", Map.of("name", "notch", "version", VERSION));
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    static List<Object> tools() {
        List<Object> t = new ArrayList<>();
        t.add(tool("notch_decisions",
                "List the decisions in force for this project: what was chosen, why, and what was ruled out. "
                        + "Check these before choosing a library, database, auth method or architecture.",
                Map.of("topic", prop("string", "Optional word to filter by, such as 'database' or 'auth'.")), List.of()));
        t.add(tool("notch_check_change",
                "Check code before writing it. Returns any decision the code goes against, such as adding a dependency the project ruled out.",
                Map.of("file_path", prop("string", "Path of the file being written, such as package.json or src/db.ts."),
                        "content", prop("string", "The new code or file content.")),
                List.of("file_path", "content")));
        t.add(tool("notch_search",
                "Search this project's past conversations and notes, for example for an error message, a file name, or why something was done.",
                Map.of("query", prop("string", "What to look for.")), List.of("query")));
        t.add(tool("notch_where_we_stopped",
                "Summarise where work stopped last time: the last session, open tasks, and any drift alerts.",
                Map.of(), List.of()));
        t.add(tool("notch_record_decision",
                "Record a decision the user has made, so future sessions and other agents follow it. Only use this when the user has clearly settled a choice.",
                Map.of("decision", prop("string", "The decision in one sentence, e.g. 'Use Postgres for billing data.'"),
                        "why", prop("string", "The reason, if given."),
                        "ruled_out", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "Options that were considered and rejected.")),
                List.of("decision")));
        t.add(tool("notch_replace_decision",
                "Replace a decision in force with a new one. The old decision stays in the project's history as replaced. "
                        + "Only use this after the user has explicitly confirmed they want to change the decision.",
                Map.of("decision_id", prop("string", "The id of the decision to replace, from notch_decisions."),
                        "new_decision", prop("string", "The new decision in one sentence."),
                        "why", prop("string", "Why the decision changed."),
                        "ruled_out", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "Options now ruled out.")),
                List.of("decision_id", "new_decision")));
        t.add(tool("notch_explain_file",
                "Explain a file in this codebase: what it does, what it depends on and what depends on it, what a change "
                        + "could affect, and why it's like this (the decisions and conversations behind it). Use before "
                        + "changing code you didn't write.",
                Map.of("path", prop("string", "The file's path from the repository root, such as src/db/pool.ts.")),
                List.of("path")));
        t.add(tool("notch_add_task",
                "Add an open task to the project, such as follow-up work agreed with the user.",
                Map.of("task", prop("string", "The task in one sentence.")), List.of("task")));
        return t;
    }

    private Map<String, Object> call(Map<String, Object> params) {
        String name = Json.str(params, "name");
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> ? Json.obj(params.get("arguments")) : Map.of();
        try {
            String text = switch (name == null ? "" : name) {
                case "notch_decisions" -> decisions(Json.str(args, "topic"));
                case "notch_check_change" -> checkChange(require(args, "file_path"), require(args, "content"));
                case "notch_search" -> backend.search(require(args, "query"));
                case "notch_where_we_stopped" -> backend.whereWeStopped();
                case "notch_record_decision" -> recordDecision(args);
                case "notch_add_task" -> "Added task " + backend.record("TASK", require(args, "task"), null, List.of()) + ".";
                case "notch_explain_file" -> backend.explainFile(require(args, "path").replace('\\', '/'));
                case "notch_replace_decision" -> "Replaced the decision. The new one is " + backend.replace(require(args, "decision_id"),
                        require(args, "new_decision"), Json.str(args, "why"), listArg(args, "ruled_out"))
                        + ", and Notch now checks changes against it.";
                default -> throw new IllegalArgumentException("Unknown tool: " + name);
            };
            return toolResult(text, false);
        } catch (NotchBackend.NotchException | IllegalArgumentException e) {
            return toolResult(e.getMessage(), true);
        }
    }

    static boolean matchesTopic(Decision d, String topic) {
        String t = topic.toLowerCase(Locale.ROOT).strip();
        String text = (d.title() + " " + String.join(" ", d.rejectedOptions())).toLowerCase(Locale.ROOT);
        if (text.contains(t)) return true;
        String singular = t.endsWith("s") && t.length() > 3 ? t.substring(0, t.length() - 1) : t;
        if (text.contains(singular)) return true;
        String category = TechCatalog.categoryOfWord(t);
        DecisionFacts f = DecisionFacts.of(d.title(), d.rejectedOptions());
        List<TechCatalog.Tech> techs = new ArrayList<>(f.chosen());
        techs.addAll(f.rejected());
        for (TechCatalog.Tech tech : techs) {
            if (tech.category().equals(category) || tech.name().toLowerCase(Locale.ROOT).contains(singular)) return true;
        }
        return false;
    }

    private String decisions(String topic) throws NotchBackend.NotchException {
        List<Decision> all = backend.decisions();
        String t = topic == null ? "" : topic.strip();
        List<Decision> shown = t.isEmpty() ? all : all.stream().filter(d -> matchesTopic(d, t)).toList();
        if (shown.isEmpty()) return t.isEmpty() ? "No decisions recorded for this project yet." : "No decisions mention \"" + topic + "\".";
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder("Decisions in force. Follow these unless the user says otherwise:\n");
        for (Decision d : shown) {
            sb.append("- ").append(d.title());
            List<String> extra = new ArrayList<>();
            if (d.decidedAt() != null) extra.add("decided " + day.format(d.decidedAt()));
            if (!d.rejectedOptions().isEmpty()) extra.add("ruled out: " + String.join(", ", d.rejectedOptions()));
            extra.add("id: " + d.id());
            sb.append(" (").append(String.join("; ", extra)).append(')');
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    private String checkChange(String path, String content) throws NotchBackend.NotchException {
        List<GuardFinding> findings = new DiffGuard().check(backend.decisions(), List.of(DiffParser.fromContent(path, content, true)));
        if (findings.isEmpty()) return "No conflicts: this change follows every decision in force.";
        StringBuilder sb = new StringBuilder("STOP: this change goes against decisions recorded for this project. Don't write it, "
                + "and don't try a variation of it. Tell the user which decision it conflicts with and ask how to proceed. If they "
                + "want to change the decision, use notch_replace_decision once they confirm.\n");
        for (GuardFinding f : findings) sb.append("- [").append(f.severity()).append("] ").append(f.location()).append(": ").append(f.message()).append('\n');
        return sb.toString().strip();
    }

    private static List<String> listArg(Map<String, Object> args, String key) {
        List<String> out = new ArrayList<>();
        if (args.get(key) instanceof List<?> l) for (Object o : l) if (o != null && !o.toString().isBlank()) out.add(o.toString());
        return out;
    }

    private String recordDecision(Map<String, Object> args) throws NotchBackend.NotchException {
        String id = backend.record("DECISION", require(args, "decision"), Json.str(args, "why"), listArg(args, "ruled_out"));
        return "Recorded decision " + id + ". Notch will now check future changes against it.";
    }

    private static String require(Map<String, Object> args, String key) {
        String v = Json.str(args, key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing argument: " + key);
        return v;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        return t;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> toolResult(String text, boolean isError) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", List.of(Map.of("type", "text", "text", text)));
        r.put("isError", isError);
        return r;
    }

    private static String ok(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return Json.write(m);
    }

    private static String error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return Json.write(m);
    }
}
