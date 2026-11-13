package dev.notch.api.llm;

import com.sun.net.httpserver.HttpServer;
import dev.notch.api.search.core.QueryTerms;
import dev.notch.guard.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The Claude and Voyage clients against fake servers: request format, quote checks, fallbacks. */
class ModelClientsTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> lastHeaders = new AtomicReference<>();
    private final List<String> replies = new ArrayList<>();
    /** Statuses for the next requests, in order; after that, {@link #status}. */
    private final List<Integer> statuses = new ArrayList<>();
    private int status = 200;
    private int requests;

    /** A fake provider: answers each request with the next queued reply. 429s say "retry now". */
    private URI start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests++;
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastHeaders.set(exchange.getRequestHeaders());
            int code = statuses.isEmpty() ? status : statuses.remove(0);
            if (code == 429) exchange.getResponseHeaders().add("Retry-After", "0");
            byte[] out = (replies.isEmpty() ? "{}" : replies.remove(0)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** A Messages API response whose text is {@code text}. */
    private static String claude(String text) {
        return Json.write(Map.of("content", List.of(Map.of("type", "text", "text", text)), "stop_reason", "end_turn"));
    }

    /** No retry budget, so error statuses fail at once. */
    private AnthropicLlmClient claudeClient(URI base, LlmClient fallback) {
        return new AnthropicLlmClient(new AnthropicLlmClient.Config("sk-ant-test", "claude-haiku-5-5", base,
                Duration.ofSeconds(5), "low", Duration.ZERO), fallback);
    }

    /** A stand-in for the offline client, so a fallback is easy to see. */
    private static final LlmClient FALLBACK = new LlmClient() {
        @Override
        public List<ExtractedMemory> extractMemories(ExtractionRequest request) {
            return List.of(new ExtractedMemory(MemoryKind.CONTEXT, "offline", null, null, List.of(), 0, 0, 1));
        }

        @Override
        public AnswerDraft answer(AnswerRequest request) {
            return new AnswerDraft("offline answer", List.of(), false);
        }

        @Override
        public String name() {
            return "offline";
        }
    };

    private static final ExtractionRequest CHAT = new ExtractionRequest("Billing", List.of(
            new ExtractionRequest.SourceMessage("user", "Mongo or Postgres for invoices?"),
            new ExtractionRequest.SourceMessage("assistant", "Postgres fits relational data better."),
            new ExtractionRequest.SourceMessage("user", "Let's go with Postgres instead of MongoDB because we need joins.")));

    @Test
    void extractionSendsAWellFormedRequestAndKeepsOnlyQuotesThatExist() throws Exception {
        replies.add(claude("""
                ```json
                {"memories": [
                  {"kind": "DECISION", "message": 2, "quote": "Let's go with Postgres instead of MongoDB because we need joins.",
                   "title": "Use Postgres for billing data.", "rationale": "joins", "rejected": ["MongoDB"], "body": null},
                  {"kind": "DECISION", "message": 1, "quote": "We will use Redis everywhere.", "title": "Invented", "rejected": []}
                ]}
                ```"""));
        List<ExtractedMemory> got = claudeClient(start(), FALLBACK).extractMemories(CHAT);
        assertEquals(1, got.size(), "the invented quote is dropped");
        ExtractedMemory m = got.get(0);
        assertEquals(MemoryKind.DECISION, m.kind());
        assertEquals(2, m.messageIndex());
        assertEquals(List.of("MongoDB"), m.rejectedOptions());
        assertEquals(CHAT.messages().get(2).text(), CHAT.messages().get(2).text().substring(m.start(), m.end()));

        assertEquals(List.of("sk-ant-test"), lastHeaders.get().get("X-api-key"));
        assertEquals(List.of(AnthropicLlmClient.API_VERSION), lastHeaders.get().get("Anthropic-version"));
        Map<String, Object> body = Json.obj(Json.parse(lastBody.get()));
        assertEquals("claude-haiku-5-5", body.get("model"));
        assertEquals(Map.of("effort", "low"), body.get("output_config"));
        assertEquals((double) AnthropicLlmClient.EXTRACT_MAX_TOKENS, body.get("max_tokens"), "room for thinking as well as the reply");
        assertTrue(((String) body.get("system")).contains("DECISION"));
        String user = (String) Json.obj(Json.arr(body.get("messages")).get(0)).get("content");
        assertTrue(user.contains("[2] user: Let's go with Postgres"), user);
    }

    @Test
    void extractionCanReplaceDecisionsAndCompleteTasksOnlyWithEvidence() throws Exception {
        ExtractionRequest request = new ExtractionRequest("UI work", List.of(
                new ExtractionRequest.SourceMessage("user", "Let's rebuild it as a decision tool instead of a meeting room."),
                new ExtractionRequest.SourceMessage("assistant", "The chat assistant is built and wired into the sidebar.")),
                List.of(new ExtractionRequest.Existing("D1", MemoryKind.DECISION, "The site is a meeting room for the family."),
                        new ExtractionRequest.Existing("T1", MemoryKind.TASK, "Build an AI chat assistant.")));
        replies.add(claude("{\"memories\": ["
                + "{\"kind\": \"DECISION\", \"message\": 0, \"quote\": \"Let's rebuild it as a decision tool instead of a meeting room.\","
                + " \"title\": \"Rebuild the site as a decision tool.\", \"rejected\": [\"meeting room\"], \"replaces\": \"D1\"},"
                + "{\"kind\": \"DONE\", \"task\": \"T1\", \"message\": 1, \"quote\": \"The chat assistant is built and wired into the sidebar.\"},"
                + "{\"kind\": \"DONE\", \"task\": \"T1\", \"message\": 1, \"quote\": \"It's all finished and shipped.\"},"
                + "{\"kind\": \"DONE\", \"task\": \"T9\", \"message\": 1, \"quote\": \"The chat assistant is built and wired into the sidebar.\"},"
                + "{\"kind\": \"DECISION\", \"message\": 0, \"quote\": \"Let's rebuild it as a decision tool instead of a meeting room.\","
                + " \"title\": \"Something else\", \"replaces\": \"T1\"}]}"));
        List<ExtractedMemory> got = claudeClient(start(), FALLBACK).extractMemories(request);
        assertEquals("D1", got.get(0).replaces());
        assertEquals(1, got.stream().filter(m -> m.completes() != null).count(), "only the completion with a real quote and a real task");
        assertEquals("T1", got.stream().filter(m -> m.completes() != null).findFirst().orElseThrow().completes());
        assertNull(got.get(got.size() - 1).replaces(), "a decision can't replace a task");
        String user = (String) Json.obj(Json.arr(Json.obj(Json.parse(lastBody.get())).get("messages")).get(0)).get("content");
        assertTrue(user.contains("[D1] decision in force: The site is a meeting room") && user.contains("[T1] open task:"), user);
    }

    @Test
    void failuresFallBackToOffline() throws Exception {  // errors and refusals
        URI base = start();
        status = 500;
        assertEquals("offline", claudeClient(base, FALLBACK).extractMemories(CHAT).get(0).title());
        status = 200;
        replies.add(Json.write(Map.of("content", List.of(), "stop_reason", "refusal")));
        assertEquals("offline", claudeClient(base, FALLBACK).extractMemories(CHAT).get(0).title());
        replies.add(claude("Sorry, I can't produce JSON for that."));
        assertEquals("offline", claudeClient(base, FALLBACK).extractMemories(CHAT).get(0).title());
        // Thinking used the whole ceiling: no text, stop_reason max_tokens.
        replies.add(Json.write(Map.of("content", List.of(Map.of("type", "thinking", "thinking", "")), "stop_reason", "max_tokens")));
        assertEquals("offline", claudeClient(base, FALLBACK).extractMemories(CHAT).get(0).title());
    }

    @Test
    void aReplyCutOffAtMaxTokensIsReportedAsSuch() throws Exception {
        URI base = start();
        replies.add(Json.write(Map.of("content", List.of(Map.of("type", "thinking", "thinking", "")), "stop_reason", "max_tokens")));
        ModelHttp.ModelException e = assertThrows(ModelHttp.ModelException.class,
                () -> claudeClient(base, FALLBACK).complete("system", "user", 300));
        assertTrue(e.getMessage().contains("max_tokens"), e.getMessage());
    }

    @Test
    void effortCanBeLeftOutForModelsWithoutIt() throws Exception {
        URI base = start();
        replies.add(claude("{\"memories\": []}"));
        new AnthropicLlmClient(new AnthropicLlmClient.Config("sk-ant-test", "claude-haiku-4-5", base,
                Duration.ofSeconds(5), " ", Duration.ZERO), FALLBACK).extractMemories(CHAT);
        assertFalse(Json.obj(Json.parse(lastBody.get())).containsKey("output_config"));
    }

    @Test
    void aFailedWindowDoesNotDiscardTheOthers() throws Exception {
        URI base = start();
        List<ExtractionRequest.SourceMessage> long_ = new ArrayList<>();
        for (int i = 0; i < 12; i++) long_.add(new ExtractionRequest.SourceMessage("user", ("filler " + i + " ").repeat(800)));
        long_.add(new ExtractionRequest.SourceMessage("user", "Let's go with Postgres instead of MongoDB because we need joins."));
        ExtractionRequest request = new ExtractionRequest("Long", long_);
        assertEquals(2, AnthropicLlmClient.windows(request).size());
        statuses.add(500);  // the first window fails
        replies.add("{}");
        replies.add(claude("{\"memories\": [{\"kind\": \"DECISION\", \"message\": 12, "
                + "\"quote\": \"Let's go with Postgres instead of MongoDB because we need joins.\", \"title\": \"Use Postgres.\"}]}"));
        List<ExtractedMemory> got = claudeClient(base, FALLBACK).extractMemories(request);
        assertEquals(List.of("Use Postgres."), got.stream().map(ExtractedMemory::title).toList(), "the second window is kept, no offline fallback");
    }

    @Test
    void rateLimitsAreRetriedWithinTheBudgetAndErrorsCarryTheProvidersMessage() throws Exception {
        URI base = start();
        AnthropicLlmClient patient = new AnthropicLlmClient(new AnthropicLlmClient.Config("sk-ant-test", "claude-haiku-5-5", base,
                Duration.ofSeconds(5), "low", Duration.ofSeconds(5)), FALLBACK);
        statuses.add(429);
        statuses.add(529);  // overloaded, without Retry-After: our own backoff
        replies.add("{}");
        replies.add("{}");
        replies.add(claude("yes"));
        assertEquals("yes", patient.complete("system", "user", 100));
        assertEquals(3, requests);

        status = 400;
        replies.add("{\"type\": \"error\", \"error\": {\"type\": \"invalid_request_error\", "
                + "\"message\": \"Your credit balance is too low to access the Anthropic API.\"}}");
        ModelHttp.ModelException e = assertThrows(ModelHttp.ModelException.class, () -> patient.complete("system", "user", 100));
        assertEquals(4, requests, "a 400 is not retried");
        assertTrue(e.getMessage().endsWith("returned 400: Your credit balance is too low to access the Anthropic API."), e.getMessage());
        assertEquals("Rate limit reached", ModelHttp.providerMessage("{\"detail\": \"Rate limit reached\"}"));
        assertNull(ModelHttp.providerMessage("<html>Bad gateway</html>"));
    }

    private static AnswerRequest question(UUID a, UUID b) {
        Instant at = Instant.parse("2026-10-05T10:00:00Z");
        return new AnswerRequest("What did we decide about the database?", QueryTerms.parse("database", at), List.of(
                new AnswerRequest.Context(1, a, "Billing", at, "Let's go with Postgres instead of MongoDB because we need joins.", true),
                new AnswerRequest.Context(2, b, "Billing", at, "Postgres fits relational data better.", false)));
    }

    @Test
    void answersCiteCheckedQuotesAndDropUnbackedMarkers() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        replies.add(claude("""
                {"answerable": true,
                 "answer": "The project chose Postgres over MongoDB because it needs joins [1]. It also uses Kafka [2].",
                 "citations": [{"n": 1, "quote": "go with postgres instead of   MongoDB"}, {"n": 2, "quote": "Kafka is great"}, {"n": 9, "quote": "x"}]}"""));
        AnswerDraft d = claudeClient(start(), FALLBACK).answer(question(a, b));
        assertTrue(d.grounded());
        assertEquals(1, d.citations().size(), "only the quote that exists in its passage survives");
        AnswerDraft.Citation c = d.citations().get(0);
        assertEquals(a, c.chunkId());
        String passage = "Let's go with Postgres instead of MongoDB because we need joins.";
        assertEquals("go with Postgres instead of MongoDB", passage.substring(c.start(), c.end()), "matched ignoring case and spacing");
        assertTrue(d.text().contains("joins [1]"), d.text());
        assertFalse(d.text().contains("[2]"), "the unbacked citation marker is removed: " + d.text());
    }

    @Test
    void unanswerableOrUncitedAnswersFallBack() throws Exception {
        URI base = start();
        replies.add(claude("{\"answerable\": false, \"answer\": \"The passages don't say.\", \"citations\": []}"));
        assertEquals("offline answer", claudeClient(base, FALLBACK).answer(question(UUID.randomUUID(), UUID.randomUUID())).text());
        replies.add(claude("{\"answerable\": true, \"answer\": \"Postgres [1].\", \"citations\": [{\"n\": 1, \"quote\": \"invented words here\"}]}"));
        assertEquals("offline answer", claudeClient(base, FALLBACK).answer(question(UUID.randomUUID(), UUID.randomUUID())).text());
    }

    @Test
    void driftConfirmationIsOnlyOverruledByAClearNo() throws Exception {
        URI base = start();
        replies.add(claude("No"));
        assertFalse(claudeClient(base, FALLBACK).confirmsDrift("Use Postgres, not MongoDB.", "We considered MongoDB and rejected it."));
        replies.add(claude("yes"));
        assertTrue(claudeClient(base, FALLBACK).confirmsDrift("Use Postgres, not MongoDB.", "Run npm install mongoose."));
        status = 503;
        assertTrue(claudeClient(base, FALLBACK).confirmsDrift("a", "b"), "without a model the rule's alert stands");
    }

    @Test
    void driftVerdictsAreRememberedButFailuresAreNot() throws Exception {
        URI base = start();
        AnthropicLlmClient client = claudeClient(base, FALLBACK);
        status = 503;
        assertTrue(client.confirmsDrift("Use sessions, not JWT.", "The analytics API uses JWT."));
        status = 200;
        replies.add(claude("no"));
        assertFalse(client.confirmsDrift("Use sessions, not JWT.", "The analytics API uses JWT."), "the failure wasn't remembered");
        int asked = requests;
        assertFalse(client.confirmsDrift("Use sessions, not JWT.", "The analytics API uses JWT."));
        assertEquals(asked, requests, "the verdict is reused: the alert doesn't flicker between page loads");
        assertTrue(((String) Json.obj(Json.parse(lastBody.get())).get("system")).contains("Answer \"no\" only when"));
    }

    @Test
    void longConversationsAreSplitIntoWindowsKeepingTheirIndices() {
        List<ExtractionRequest.SourceMessage> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(new ExtractionRequest.SourceMessage("user", "x".repeat(5_000)));
        List<int[]> w = AnthropicLlmClient.windows(new ExtractionRequest("Long", many));
        assertTrue(w.size() >= 3);
        assertEquals(0, w.get(0)[0]);
        assertEquals(30, w.get(w.size() - 1)[1]);
        for (int i = 1; i < w.size(); i++) assertEquals(w.get(i - 1)[1], w.get(i)[0], "windows are contiguous");
    }

    @Test
    void quotesAreLocatedExactlyOrLoosely() {  // boundary
        assertArrayEquals(new int[] {4, 12}, AnthropicLlmClient.locate("use Postgres now", "Postgres"));
        assertNotNull(AnthropicLlmClient.locate("Use  POSTGRES\nfor billing", "use postgres for"));
        assertNull(AnthropicLlmClient.locate("Use Postgres", "Use MySQL"));
        assertNull(AnthropicLlmClient.locate("Use Postgres", "Use"), "too short to mean anything");
        assertNull(AnthropicLlmClient.locate(null, "anything at all"));
    }

    @Test
    void providersAreChosenFromSettingsAndKeys() {
        assertTrue(ProviderChoice.use("auto", "anthropic", "sk-ant-x", "ANTHROPIC_API_KEY"));
        assertTrue(ProviderChoice.use(null, "anthropic", "sk-ant-x", "ANTHROPIC_API_KEY"), "auto is the default");
        assertFalse(ProviderChoice.use("auto", "anthropic", "", "ANTHROPIC_API_KEY"), "no key: stay offline");
        assertFalse(ProviderChoice.use("offline", "anthropic", "sk-ant-x", "ANTHROPIC_API_KEY"), "offline wins over a key");
        assertTrue(ProviderChoice.use("Voyage", "voyage", "pa-x", "VOYAGE_API_KEY"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ProviderChoice.use("anthropic", "anthropic", null, "ANTHROPIC_API_KEY"));
        assertTrue(e.getMessage().contains("ANTHROPIC_API_KEY"), "chosen without a key fails loudly at startup");
    }

    // ------------------------------------------------------------------ Voyage

    private static String voyage(int dims, int... indices) {
        List<Object> data = new ArrayList<>();
        for (int idx : indices) {
            List<Double> v = new ArrayList<>();
            for (int k = 0; k < dims; k++) v.add(k == idx % dims ? 3.0 : 0.0);
            data.add(Map.of("object", "embedding", "embedding", v, "index", idx));
        }
        return Json.write(Map.of("object", "list", "data", data));
    }

    @Test
    void voyageEmbedsDocumentsAndQueriesDifferently() throws Exception {
        URI base = start();
        VoyageEmbeddingClient client = new VoyageEmbeddingClient(new VoyageEmbeddingClient.Config("pa-test", "voyage-4-lite", 8, base, Duration.ofSeconds(5)));
        replies.add(voyage(8, 1, 0));  // out of order on purpose
        List<float[]> docs = client.embed(List.of("first passage", "  ", "second passage"));
        assertNull(docs.get(1), "blank text gets no vector");
        assertEquals(1.0f, docs.get(0)[0], 1e-6, "results are placed by index and normalised");
        assertEquals(1.0f, docs.get(2)[1], 1e-6);
        Map<String, Object> body = Json.obj(Json.parse(lastBody.get()));
        assertEquals("document", body.get("input_type"));
        assertEquals(8.0, body.get("output_dimension"));
        assertEquals(List.of("first passage", "second passage"), body.get("input"));
        assertEquals(List.of("Bearer pa-test"), lastHeaders.get().get("Authorization"));

        replies.add(voyage(8, 0));
        assertEquals(8, client.embedQuery("how do we store money?").length);
        assertEquals("query", Json.obj(Json.parse(lastBody.get())).get("input_type"));
        assertEquals("voyage-4-lite-8", client.name());
    }

    @Test
    void voyageFailuresAreThrownForTheCallerToHandle() throws Exception {
        URI base = start();
        VoyageEmbeddingClient client = new VoyageEmbeddingClient(new VoyageEmbeddingClient.Config("pa-test", null, 8, base,
                Duration.ofSeconds(5), Duration.ZERO));
        replies.add(voyage(4, 0));  // wrong size
        assertThrows(IllegalStateException.class, () -> client.embed(List.of("text here")));
        status = 429;
        assertThrows(IllegalStateException.class, () -> client.embed(List.of("text here")));
    }

    @Test
    void voyageWaitsOutRateLimitsForPassagesButNotForSearch() throws Exception {
        URI base = start();
        VoyageEmbeddingClient client = new VoyageEmbeddingClient(new VoyageEmbeddingClient.Config("pa-test", null, 8, base,
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        statuses.add(429);
        statuses.add(429);
        replies.add("{\"detail\": \"rate limited\"}");
        replies.add("{\"detail\": \"rate limited\"}");
        replies.add(voyage(8, 0));
        assertEquals(8, client.embed(List.of("a stored passage")).get(0).length);
        assertEquals(3, requests, "two rate limits, then the vectors");

        statuses.add(429);
        replies.add("{\"detail\": \"rate limited\"}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.embedQuery("search text"));
        assertEquals(4, requests, "a search doesn't wait: exact matching answers meanwhile");
        assertTrue(e.getMessage().contains("429: rate limited"), e.getMessage());
    }
}
