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

    @Override
    public String record(String kind, String title, String rationale, List<String> ruledOut) throws NotchException {
        requireProject();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("title", title);
        if (rationale != null && !rationale.isBlank()) body.put("rationale", rationale);
        body.put("rejectedOptions", ruledOut == null ? List.of() : ruledOut);
        Map<String, Object> m = Json.obj(Json.parse(send("POST", "/api/projects/" + enc(config.project) + "/memories", Json.write(body))));
        return Json.str(m, "id");
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
