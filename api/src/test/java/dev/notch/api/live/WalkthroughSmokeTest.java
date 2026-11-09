package dev.notch.api.live;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static dev.notch.api.live.LiveApi.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the README walkthrough end to end against a live API, one step per test, so a failure
 * names the exact feature that broke. Uses a fresh test user, so results don't depend on
 * earlier runs. Skipped unless {@code NOTCH_API_URL} is set.
 */
@EnabledIfEnvironmentVariable(named = "NOTCH_API_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WalkthroughSmokeTest {

    private static String token;
    private static String p;  // "/api/projects/{id}"

    @BeforeAll
    static void setUp() throws Exception {
        Res health = get("/actuator/health", null);
        assertEquals(200, health.status(), "API not healthy at " + BASE + ": " + health.body());
        token = login("smoke-" + Long.toString(System.nanoTime(), 36));
        Res created = call("POST", "/api/projects", token, "{\"name\":\"Billing service\"}");
        assertEquals(201, created.status(), created.body());
        p = "/api/projects/" + created.json().get("id");
    }

    private static Res importAndWait(String file) throws Exception {
        Res r = upload(p + "/imports", token, sample(file));
        assertEquals(202, r.status(), "import of " + file + ": " + r.body());
        String id = (String) r.json().get("id");
        Res done = waitFor("import of " + file + " to finish", 60, () -> get(p + "/imports/" + id, token),
                x -> x.status() == 200 && !"PROCESSING".equals(x.json().get("status")));
        assertEquals("READY", done.json().get("status"), "import of " + file + " failed: " + done.body());
        return r;
    }

    @Test
    @Order(1)
    void importsChatGptExportAndRedactsTheKey() throws Exception {
        Res r = importAndWait("chatgpt-conversations.json");
        assertEquals("chatgpt", r.json().get("format"));
        assertEquals(2, r.num("conversationsAdded"));
        assertEquals(11, r.num("messagesAdded"));
        assertEquals(1, r.num("secretsRedacted"));
        assertTrue(r.num("chunksAdded") > 0);
    }

    @Test
    @Order(2)
    void pastedCopySkipsDuplicateChunks() throws Exception {
        Res r = importAndWait("pasted-billing-chat.txt");
        assertEquals(3, r.num("duplicateChunks"), r.body());
        assertEquals(0, r.num("chunksAdded"), r.body());
    }

    @Test
    @Order(3)
    void reimportSkipsKnownMessages() throws Exception {
        Res r = upload(p + "/imports", token, sample("chatgpt-conversations.json"));
        assertEquals(202, r.status(), r.body());
        assertEquals(11, r.num("messagesSkipped"));
        assertEquals(0, r.num("messagesAdded"));
        assertEquals("READY", r.json().get("status"));
    }

    @Test
    @Order(4)
    void importsClaudeExportAndMarkdownNote() throws Exception {
        assertEquals("claude", importAndWait("claude-conversations.json").json().get("format"));
        assertEquals("markdown", importAndWait("billing-notes.md").json().get("format"));
    }

    @Test
    @Order(5)
    void extractsDecisionsTasksAndFixes() throws Exception {
        String decisions = get(p + "/memories?kind=decision", token).body();
        assertTrue(decisions.contains("Postgres instead of MongoDB"), decisions);
        assertTrue(decisions.contains("server-side sessions"), decisions);
        assertTrue(get(p + "/memories?kind=task", token).list().size() >= 3);
        assertTrue(get(p + "/memories?kind=fix", token).body().contains("ECONNREFUSED"));
    }

    @Test
    @Order(6)
    void searchFindsTheExactErrorCode() throws Exception {
        Res r = get(p + "/search?q=ECONNREFUSED", token);
        assertEquals(200, r.status(), r.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) r.json().get("hits");
        assertFalse(hits.isEmpty(), r.body());
        assertTrue(((String) hits.get(0).get("text")).contains("ECONNREFUSED"));
        assertNotNull(hits.get(0).get("why"));
    }

    @Test
    @Order(7)
    void askGivesACitedAnswer() throws Exception {
        Res r = call("POST", p + "/ask", token, "{\"question\":\"What did we decide about the database?\"}");
        assertEquals(200, r.status(), r.body());
        assertEquals(Boolean.TRUE, r.json().get("grounded"), r.body());
        assertTrue(((String) r.json().get("answer")).contains("Postgres"), r.body());
        assertFalse(((List<?>) r.json().get("citations")).isEmpty());
    }

    @Test
    @Order(8)
    void autocompleteAndIndexStats() throws Exception {
        assertTrue(get(p + "/autocomplete?prefix=econ", token).body().contains("ECONNREFUSED"));
        assertEquals(200, get(p + "/index", token).status());
        assertEquals(200, get(p + "/threads", token).status());
    }

    @Test
    @Order(9)
    void driftIsDetectedAfterTheAnalyticsChat() throws Exception {
        importAndWait("analytics-chat.txt");
        // The index rebuilds in the background after the import, so poll briefly.
        Res r = waitFor("the MongoDB drift alert", 30, () -> get(p + "/drift", token),
                x -> x.status() == 200 && x.body().contains("starts using MongoDB"));
        assertTrue(r.body().contains("DECISION_CONFLICT"), r.body());
    }

    @Test
    @Order(10)
    void supersedingResolvesTheConflict() throws Exception {
        List<Map<String, Object>> decisions = get(p + "/memories?kind=decision", token).list();
        String sessions = idOf(decisions, "server-side sessions");
        String jwt = idOf(decisions, "use JWT");
        Res r = call("POST", "/api/memories/" + sessions + "/supersede", token, "{\"replacementId\":\"" + jwt + "\"}");
        assertEquals(200, r.status(), r.body());
        assertEquals(2, get("/api/memories/" + jwt + "/history", token).list().size());
        assertFalse(get(p + "/drift", token).body().contains("DECISION_CONFLICT"));
    }

    @Test
    @Order(11)
    void resumeContextPackAndAgentFiles() throws Exception {
        Res resume = get(p + "/resume", token);
        assertEquals(200, resume.status(), resume.body());
        assertFalse(((List<?>) resume.json().get("openTasks")).isEmpty());
        assertTrue(((String) get(p + "/context-pack", token).json().get("markdown")).contains("## Decisions in force"));
        assertTrue(get(p + "/export/agents-md", token).body().startsWith("# AGENTS.md"));
        assertTrue(get(p + "/export/claude-md", token).body().startsWith("# CLAUDE.md"));
    }

    @Test
    @Order(12)
    void usageReflectsTheQuestionAsked() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> remaining = (Map<String, Object>) get("/api/usage", token).json().get("remaining");
        assertEquals(29, ((Number) remaining.get("question")).intValue());
    }

    @Test
    @Order(13)
    void validationErrorsAreClear() throws Exception {
        Res empty = call("POST", "/api/projects", token, "{\"name\":\"\"}");
        assertEquals(400, empty.status());
        Res unknown = get("/api/projects/00000000-0000-0000-0000-00000000dead", token);
        assertEquals(404, unknown.status());
        assertTrue(unknown.body().contains("\"code\":\"not_found\""), unknown.body());
    }

    private static String idOf(List<Map<String, Object>> memories, String titleFragment) {
        return memories.stream().filter(m -> ((String) m.get("title")).contains(titleFragment))
                .map(m -> (String) m.get("id")).findFirst()
                .orElseThrow(() -> new AssertionError("no decision containing '" + titleFragment + "' in " + memories));
    }
}
