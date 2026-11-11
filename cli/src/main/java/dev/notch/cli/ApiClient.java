package dev.notch.cli;

import dev.notch.guard.Decision;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the Notch API with a personal token. Active decisions are cached on disk after
 * every successful fetch, so a check still works offline (with a warning) and a commit is
 * never held up by the network.
 */
public final class ApiClient implements NotchBackend {

    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final boolean offline;
    private String warning;

    public ApiClient(Config config, boolean offline) {
        this.config = config;
        this.offline = offline;
    }

    /** Set when decisions came from the cache instead of the server. */
    public String warning() {
        return warning;
    }

    @Override
    public List<Decision> decisions() throws NotchException {
        requireProject();
        Path cache = config.cacheDir.resolve("decisions-" + config.project + ".json");
        if (!offline) {
            try {
                String body = get("/api/projects/" + enc(config.project) + "/memories?kind=decision&status=active&limit=500");
                Files.createDirectories(cache.getParent());
                Files.writeString(cache, body, StandardCharsets.UTF_8);
                return parseDecisions(body);
            } catch (NotchException e) {
                if (!Files.isRegularFile(cache)) throw e;
                warning = e.getMessage() + " Using the decisions saved from the last successful check.";
            } catch (IOException e) {
                warning = "Couldn't save the decisions for offline use: " + e.getMessage();
            }
        }
        try {
            if (!Files.isRegularFile(cache)) {
                throw new NotchException("No saved decisions for this project yet. Run a check once while online.");
            }
            return parseDecisions(Files.readString(cache, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new NotchException("Couldn't read the saved decisions: " + e.getMessage(), e);
        }
    }

    static List<Decision> parseDecisions(String body) {
        List<Decision> out = new ArrayList<>();
        for (Object o : Json.arr(Json.parse(body))) {
            Map<String, Object> m = Json.obj(o);
            if (!"DECISION".equals(m.get("kind")) || !"ACTIVE".equals(m.get("status"))) continue;
            List<String> rejected = new ArrayList<>();
            if (m.get("rejectedOptions") instanceof List<?> l) for (Object r : l) if (r != null) rejected.add(r.toString());
            out.add(new Decision(Json.str(m, "id"), Json.str(m, "title"), rejected, instant(Json.str(m, "createdAt"))));
            // Decisions carry their id so an agent can ask to replace one (with the user's go-ahead).
        }
        return out;
    }

    @Override
    public String search(String query) throws NotchException {
        requireProject();
        Map<String, Object> r = Json.obj(Json.parse(get("/api/projects/" + enc(config.project) + "/search?k=6&q=" + enc(query))));
        List<Object> hits = Json.arr(r.get("hits"));
        if (hits.isEmpty()) return "Nothing in this project matches \"" + query + "\".";
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Object h : hits) {
            Map<String, Object> hit = Json.obj(h);
            Map<String, Object> src = Json.obj(hit.get("source"));
            sb.append(n++).append(". ").append(Json.str(src, "conversationTitle")).append(" (")
                    .append(day(Json.str(src, "sentAt"))).append(", ").append(Json.str(src, "role")).append(")")
                    .append(Boolean.TRUE.equals(hit.get("decision")) ? " [decision]" : "").append('\n')
                    .append("   ").append(Json.str(hit, "text").replace("\n", "\n   ")).append("\n\n");
        }
        return sb.toString().strip();
    }

    @Override
    public String whereWeStopped() throws NotchException {
        requireProject();
        Map<String, Object> r = Json.obj(Json.parse(get("/api/projects/" + enc(config.project) + "/resume")));
        StringBuilder sb = new StringBuilder();
        for (Object line : Json.arr(r.get("whereYouStopped"))) sb.append(line).append('\n');
        List<Object> tasks = Json.arr(r.get("openTasks"));
        if (!tasks.isEmpty()) {
            sb.append("\nOpen tasks, most relevant first:\n");
            for (Object t : tasks) sb.append("- ").append(Json.str(Json.obj(t), "title")).append('\n');
        }
        Object drift = r.get("driftAlerts");
        if (drift instanceof Number d && d.intValue() > 0) {
            sb.append("\n").append(d.intValue()).append(" drift alert(s) are open in Notch.\n");
        }
        return sb.length() == 0 ? "No sessions recorded for this project yet." : sb.toString().strip();
    }

    /** Shown in the Ledger as where a memory came from. */
    static final String FROM_AGENT = "Recorded by your coding agent.";

    @Override
    public String record(String kind, String title, String rationale, List<String> ruledOut) throws NotchException {
        requireProject();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("title", title);
        body.put("body", FROM_AGENT);
        if (rationale != null && !rationale.isBlank()) body.put("rationale", rationale);
        body.put("rejectedOptions", ruledOut == null ? List.of() : ruledOut);
        Map<String, Object> m = Json.obj(Json.parse(send("POST", "/api/projects/" + enc(config.project) + "/memories", Json.write(body))));
        return Json.str(m, "id");
    }

    @Override
    public String replace(String decisionId, String title, String rationale, List<String> ruledOut) throws NotchException {
        if (decisionId == null || !decisionId.matches("[0-9a-fA-F-]{36}")) {
            throw new NotchException("Use the decision's id from notch_decisions.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("body", FROM_AGENT);
        if (rationale != null && !rationale.isBlank()) body.put("rationale", rationale);
        body.put("rejectedOptions", ruledOut == null ? List.of() : ruledOut);
        Map<String, Object> m = Json.obj(Json.parse(send("POST", "/api/memories/" + enc(decisionId) + "/supersede", Json.write(body))));
        return Json.str(m, "id");
    }

    /** Uploads a code map (JSON as written by CodeMapJson). */
    public String uploadMap(String label, String mapJson) throws NotchException {
        requireProject();
        String body = "{\"label\":" + Json.write(label) + ",\"map\":" + mapJson + "}";
        return Json.str(Json.obj(Json.parse(send("POST", "/api/projects/" + enc(config.project) + "/code-maps", body))), "id");
    }

    @Override
    public String explainFile(String path) throws NotchException {
        requireProject();
        Map<String, Object> v;
        try {
            v = Json.obj(Json.parse(get("/api/projects/" + enc(config.project) + "/code-map/file?path=" + enc(path))));
        } catch (NotchException e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("wasn't found")) {
                throw new NotchException("Notch has no map entry for " + path + ". Run notch map to update the code map, then ask again.");
            }
            throw e;
        }
        return describe(v);
    }

    /** Turns a file view into plain text an agent can read. */
    static String describe(Map<String, Object> v) {
        Map<String, Object> f = Json.obj(v.get("file"));
        StringBuilder sb = new StringBuilder();
        sb.append(Json.str(f, "path")).append('\n');
        String summary = Json.str(f, "summary");
        if (summary != null && !summary.isBlank()) sb.append("What it does: ").append(summary).append('\n');
        sb.append("Role: ").append(Json.str(f, "role").toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        if (f.get("route") != null) sb.append(", route ").append(Json.str(f, "route"));
        sb.append('\n');
        List<Object> endpoints = Json.arr(f.get("endpoints"));
        if (!endpoints.isEmpty()) sb.append("Endpoints: ").append(join(endpoints)).append('\n');
        List<Object> imports = Json.arr(f.get("imports"));
        List<Object> importedBy = Json.arr(f.get("importedBy"));
        List<Object> affected = Json.arr(v.get("affected"));
        sb.append("How it fits: imports ").append(imports.size()).append(" project files")
                .append(imports.isEmpty() ? "" : " (" + join(imports.subList(0, Math.min(5, imports.size()))) + ")")
                .append("; used by ").append(importedBy.size())
                .append(importedBy.isEmpty() ? "" : " (" + join(importedBy.subList(0, Math.min(5, importedBy.size()))) + ")")
                .append("; changing it could affect ").append(affected.size()).append(" files.\n");
        List<Object> tools = Json.arr(f.get("tools"));
        if (!tools.isEmpty()) sb.append("Uses: ").append(join(tools)).append('\n');
        List<Object> decisions = Json.arr(v.get("decisions"));
        if (!decisions.isEmpty()) {
            sb.append("Why, from decisions:\n");
            for (Object o : decisions) {
                Map<String, Object> d = Json.obj(o);
                boolean against = "GOES_AGAINST".equals(d.get("kind"));
                sb.append("- ").append(against ? "GOES AGAINST: uses " : "Follows: uses ").append(Json.str(d, "tool"))
                        .append(" per \u201c").append(Json.str(d, "decisionTitle")).append("\u201d\n");
            }
        }
        List<Object> mentions = Json.arr(v.get("mentions"));
        if (!mentions.isEmpty()) {
            sb.append("Why, from conversations:\n");
            for (Object o : mentions.subList(0, Math.min(4, mentions.size()))) {
                Map<String, Object> m = Json.obj(o);
                sb.append("- \u201c").append(Json.str(m, "conversationTitle")).append("\u201d (").append(day(Json.str(m, "sentAt")))
                        .append("): ").append(Json.str(m, "text").replace('\n', ' ')).append('\n');
            }
        }
        if (decisions.isEmpty() && mentions.isEmpty()) sb.append("No decisions or conversations in Notch mention this file yet.\n");
        return sb.toString().strip();
    }

    private static String join(List<Object> items) {
        return String.join(", ", items.stream().map(String::valueOf).toList());
    }

    @Override
    public String projectName() throws NotchException {
        requireProject();
        return Json.str(Json.obj(Json.parse(get("/api/projects/" + enc(config.project)))), "name");
    }

    public String whoAmI() throws NotchException {
        return Json.str(Json.obj(Json.parse(get("/api/me"))), "handle");
    }

    private String get(String path) throws NotchException {
        return send("GET", path, null);
    }

    private String send(String method, String path, String json) throws NotchException {
        if (config.token == null) {
            throw new NotchException("Not signed in. Create a personal token in Notch (Settings), then run: notch login --token <token>");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.api + path))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + config.token)
                .header("Accept", "application/json");
        if (json != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new NotchException("Couldn't reach Notch at " + config.api + ".", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotchException("Interrupted while contacting Notch.", e);
        }
        if (r.statusCode() == 401) throw new NotchException("Notch didn't accept the token. Create a new one in Settings and run notch login again.");
        if (r.statusCode() == 404) throw new NotchException("Project " + config.project + " wasn't found for this account. Check notch link.");
        if (r.statusCode() >= 400) throw new NotchException("Notch returned " + r.statusCode() + ": " + detail(r.body()));
        return r.body();
    }

    private void requireProject() throws NotchException {
        if (config.project == null) {
            throw new NotchException("This repository isn't linked to a Notch project. Run: notch link <project-id>");
        }
    }

    private static String detail(String body) {
        try {
            String d = Json.str(Json.obj(Json.parse(body)), "detail");
            return d == null ? body : d;
        } catch (RuntimeException e) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    private static Instant instant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String day(String iso) {
        return iso == null || iso.length() < 10 ? "?" : iso.substring(0, 10);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
