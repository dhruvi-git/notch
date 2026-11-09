package dev.notch.api.live;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static dev.notch.api.live.LiveApi.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Black-box proof that one user can never reach another user's data, run against a live API
 * in the local profile. Alice creates a project, imports a file, records a decision and makes
 * a token. Bob then tries every endpoint against Alice's ids and must always get 404 (never
 * 403, which would confirm the id exists). Skipped unless {@code NOTCH_API_URL} is set.
 */
@EnabledIfEnvironmentVariable(named = "NOTCH_API_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossUserIsolationTest {

    private static String alice;
    private static String bob;
    private static String project;
    private static String memory;
    private static String importId;
    private static String aliceTokenId;

    @BeforeAll
    static void setUp() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        alice = login("alice-" + suffix);
        bob = login("bob-" + suffix);

        Res p = call("POST", "/api/projects", alice, "{\"name\":\"Alice private " + suffix + "\"}");
        assertEquals(201, p.status(), p.body());
        project = (String) p.json().get("id");

        Res imp = upload("/api/projects/" + project + "/imports", alice, sample("claude-conversations.json"));
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
        assertEquals(200, get("/api/projects/" + project, alice).status());
        assertEquals(200, get("/api/memories/" + memory + "/history", alice).status());
    }

    @Test
    @Order(2)
    void bobCannotSeeAlicesProjectInAnyWay() throws Exception {
        String p = "/api/projects/" + project;
        for (String path : List.of(p, p + "/imports", p + "/imports/" + importId, p + "/conversations",
                p + "/memories", p + "/search?q=postgres", p + "/autocomplete?prefix=a", p + "/threads",
                p + "/index", p + "/drift", p + "/resume", p + "/context-pack", p + "/export/agents-md",
                p + "/export/claude-md")) {
            Res r = get(path, bob);
            assertEquals(404, r.status(), "GET " + path + " leaked: " + r.body());
        }
    }

    @Test
    @Order(3)
    void bobCannotWriteToAlicesProject() throws Exception {
        String p = "/api/projects/" + project;
        assertEquals(404, call("POST", p + "/ask", bob, "{\"question\":\"what did we decide?\"}").status());
        assertEquals(404, call("POST", p + "/memories", bob, "{\"kind\":\"task\",\"title\":\"sneaky\"}").status());
        assertEquals(404, upload(p + "/imports", bob, sample("billing-notes.md")).status());
        assertEquals(404, call("POST", p + "/drift/0123456789abcdef01234567/dismiss", bob, null).status());
    }

    @Test
    @Order(4)
    void bobCannotTouchAlicesMemoriesOrTokens() throws Exception {
        assertEquals(404, get("/api/memories/" + memory + "/history", bob).status());
        assertEquals(404, call("PATCH", "/api/memories/" + memory, bob, "{\"status\":\"done\"}").status());
        assertEquals(404, call("POST", "/api/memories/" + memory + "/supersede", bob, "{\"title\":\"hijack\"}").status());
        assertEquals(404, call("DELETE", "/api/tokens/" + aliceTokenId, bob, null).status());
    }

    @Test
    @Order(5)
    void bobsListsDoNotIncludeAlicesData() throws Exception {
        assertFalse(get("/api/projects", bob).body().contains(project));
        assertFalse(get("/api/tokens", bob).body().contains(aliceTokenId));
    }

    @Test
    @Order(6)
    void badAndRevokedTokensAreRejected() throws Exception {
        assertEquals(401, get("/api/projects", "ntch_ses_" + "x".repeat(43)).status());
        assertEquals(401, get("/api/projects", "not-a-token").status());
        String temp = login("temp-" + System.nanoTime());
        assertEquals(200, get("/api/me", temp).status());
        assertEquals(204, call("POST", "/api/logout", temp, null).status());
        assertEquals(401, get("/api/me", temp).status());
    }

    @Test
    @Order(7)
    void alicesDataIsUnchangedAfterBobsAttempts() throws Exception {
        Res h = get("/api/memories/" + memory + "/history", alice);
        assertEquals(200, h.status());
        assertTrue(h.body().contains("\"status\":\"ACTIVE\""), h.body());
        assertFalse(h.body().contains("hijack"));
    }
}
