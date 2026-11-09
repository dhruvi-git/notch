package dev.notch.api.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Black-box proof that one user can never reach another user's data, run against a live API
 * in the local profile:
 *
 * <pre>
 *   docker compose up -d && mvn -pl api spring-boot:run       # in one terminal
 *   NOTCH_API_URL=http://localhost:8080 mvn -pl api test -Dtest=CrossUserIsolationTest
 * </pre>
 *
 * Alice creates a project, imports a file and records a decision. Bob then tries every
 * endpoint against Alice's ids and must always get 404 (never 403, which would confirm the id
 * exists). Skipped when {@code NOTCH_API_URL} is not set.
 */
@EnabledIfEnvironmentVariable(named = "NOTCH_API_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossUserIsolationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String BASE = System.getenv("NOTCH_API_URL");

    private static String alice;
    private static String bob;
    private static String project;
    private static String memory;
    private static String importId;
    private static String aliceTokenId;

    record Res(int status, String body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json() {
            return JSON.readValue(body, Map.class);
        }
    }

    private static Res call(String method, String path, String token, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    private static Res upload(String path, String token, Path file) throws Exception {
        String boundary = "----notch" + UUID.randomUUID();
        byte[] content = Files.readAllBytes(file);
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + file.getFileName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes();
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes();
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        HttpRequest r = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> res = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
        return new Res(res.statusCode(), res.body());
    }

    private static String login(String handle) throws Exception {
        Res r = call("POST", "/auth/dev-login", null, "{\"handle\":\"" + handle + "\"}");
        assertEquals(200, r.status(), "dev-login needs the API running with the local profile: " + r.body());
        return (String) r.json().get("token");
    }

    @BeforeAll
    static void setUp() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        alice = login("alice-" + suffix);
        bob = login("bob-" + suffix);

        Res p = call("POST", "/api/projects", alice, "{\"name\":\"Alice private " + suffix + "\"}");
        assertEquals(201, p.status(), p.body());
        project = (String) p.json().get("id");

        Res imp = upload("/api/projects/" + project + "/imports", alice, Path.of("../samples/claude-conversations.json"));
        assertEquals(202, imp.status(), imp.body());
        importId = (String) imp.json().get("id");

        Res m = call("POST", "/api/projects/" + project + "/memories", alice,
                "{\"kind\":\"decision\",\"title\":\"We chose Postgres instead of MongoDB.\",\"rejectedOptions\":[\"MongoDB\"]}");
        assertEquals(201, m.status(), m.body());
        memory = (String) m.json().get("id");

        Res t = call("POST", "/api/tokens", alice, "{\"name\":\"alice laptop\"}");
        assertEquals(201, t.status(), t.body());
        aliceTokenId = (String) t.json().get("id");
    }

    @Test
    @Order(1)
    void aliceCanReadHerOwnData() throws Exception {
        assertEquals(200, call("GET", "/api/projects/" + project, alice, null).status());
        assertEquals(200, call("GET", "/api/memories/" + memory + "/history", alice, null).status());
    }

    @Test
    @Order(2)
    void bobCannotSeeAlicesProjectInAnyWay() throws Exception {
        String p = "/api/projects/" + project;
        for (String path : List.of(p, p + "/imports", p + "/imports/" + importId, p + "/conversations",
                p + "/memories", p + "/search?q=postgres", p + "/autocomplete?prefix=a", p + "/threads",
                p + "/index", p + "/drift", p + "/resume", p + "/context-pack", p + "/export/agents-md",
                p + "/export/claude-md")) {
            Res r = call("GET", path, bob, null);
            assertEquals(404, r.status(), "GET " + path + " leaked: " + r.body());
        }
    }

    @Test
    @Order(3)
    void bobCannotWriteToAlicesProject() throws Exception {
        String p = "/api/projects/" + project;
        assertEquals(404, call("POST", p + "/ask", bob, "{\"question\":\"what did we decide?\"}").status());
        assertEquals(404, call("POST", p + "/memories", bob, "{\"kind\":\"task\",\"title\":\"sneaky\"}").status());
        assertEquals(404, upload(p + "/imports", bob, Path.of("../samples/billing-notes.md")).status());
        assertEquals(404, call("POST", p + "/drift/0123456789abcdef01234567/dismiss", bob, null).status());
    }

    @Test
    @Order(4)
    void bobCannotTouchAlicesMemoriesOrTokens() throws Exception {
        assertEquals(404, call("GET", "/api/memories/" + memory + "/history", bob, null).status());
        assertEquals(404, call("PATCH", "/api/memories/" + memory, bob, "{\"status\":\"done\"}").status());
        assertEquals(404, call("POST", "/api/memories/" + memory + "/supersede", bob, "{\"title\":\"hijack\"}").status());
        assertEquals(404, call("DELETE", "/api/tokens/" + aliceTokenId, bob, null).status());
    }

    @Test
    @Order(5)
    void bobsListsDoNotIncludeAlicesData() throws Exception {
        assertFalse(call("GET", "/api/projects", bob, null).body().contains(project));
        assertFalse(call("GET", "/api/tokens", bob, null).body().contains(aliceTokenId));
    }

    @Test
    @Order(6)
    void badAndRevokedTokensAreRejected() throws Exception {
        assertEquals(401, call("GET", "/api/projects", "ntch_ses_" + "x".repeat(43), null).status());
        assertEquals(401, call("GET", "/api/projects", "not-a-token", null).status());
        String temp = login("temp-" + System.nanoTime());
        assertEquals(200, call("GET", "/api/me", temp, null).status());
        assertEquals(204, call("POST", "/api/logout", temp, null).status());
        assertEquals(401, call("GET", "/api/me", temp, null).status());
    }

    @Test
    @Order(7)
    void aliceDataIsUnchangedAfterBobsAttempts() throws Exception {
        Res h = call("GET", "/api/memories/" + memory + "/history", alice, null);
        assertEquals(200, h.status());
        assertTrue(h.body().contains("\"status\":\"ACTIVE\""), h.body());
        assertFalse(h.body().contains("hijack"));
    }
}
