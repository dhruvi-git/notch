package dev.notch.api.llm;

import dev.notch.guard.Json;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude, through the Messages API, for extracting memories and writing answers.
 *
 * <p>The model is asked for JSON with an exact quote behind every memory and citation. Each quote
 * is checked against the source text and dropped if it isn't there, so source highlighting stays
 * truthful whatever the model writes. Any failure (network, error status, refusal, unreadable
 * output) falls back to the offline client, so Notch keeps working. Logs never include content.
 */
public final class AnthropicLlmClient implements LlmClient {

    public static final String API_VERSION = "2023-06-01";

    public record Config(String apiKey, String model, URI baseUrl, Duration timeout) {
        public Config {
            if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("an API key is required");
            if (model == null || model.isBlank()) model = "claude-haiku-5-5";
            if (baseUrl == null) baseUrl = URI.create("https://api.anthropic.com");
            if (timeout == null) timeout = Duration.ofSeconds(60);
        }
    }

    static final int MAX_MESSAGE_CHARS = 6_000;
    static final int MAX_WINDOW_CHARS = 60_000;
    static final int MAX_MEMORIES = 25;

    private static final System.Logger LOG = System.getLogger(AnthropicLlmClient.class.getName());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final Config config;
    private final ModelHttp http;
    private final LlmClient fallback;

    public AnthropicLlmClient(Config config, LlmClient fallback) {
        this.config = config;
        this.http = new ModelHttp(config.timeout());
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return config.model();
    }

    // ------------------------------------------------------------------ extraction

    static final String EXTRACT_SYSTEM = """
            You read a developer's conversation with an AI assistant and find what is worth remembering about \
            their project. Find:
            - DECISION: a choice the developer made or agreed to (a tool, database, library, approach). Not \
            suggestions the developer didn't take up.
            - TASK: work the developer says is still to do.
            - FIX: an error or problem and how it was solved.
            - CONTEXT: a lasting fact about the project (its stack, a constraint, a convention).
            For each, give: "kind"; "message", the number of the message it comes from; "quote", one sentence \
            copied exactly, character for character, from that message, that states it; "title", the point in \
            one plain sentence; "rationale", the reason if one is given, else null; "rejected", the options \
            explicitly ruled out, as short names (empty if none); "body", for a FIX the solution in one or two \
            sentences, else null.
            Only include what the conversation states. Return only JSON, no other text: \
            {"memories": [ ... ]}. If nothing qualifies, return {"memories": []}.""";

    @Override
    public List<ExtractedMemory> extractMemories(ExtractionRequest request) {
        List<ExtractedMemory> out = new ArrayList<>();
        try {
            for (int[] window : windows(request)) {
                out.addAll(extractWindow(request, window[0], window[1]));
                if (out.size() >= MAX_MEMORIES) break;
            }
            return out.size() > MAX_MEMORIES ? out.subList(0, MAX_MEMORIES) : out;
        } catch (ModelHttp.ModelException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Extraction fell back to offline: {0}", e.getMessage());
            return fallback.extractMemories(request);
        }
    }

    /** Groups messages into windows small enough for one request; indices stay the conversation's. */
    static List<int[]> windows(ExtractionRequest request) {
        List<int[]> out = new ArrayList<>();
        int start = 0;
        int size = 0;
        for (int i = 0; i < request.messages().size(); i++) {
            int len = Math.min(MAX_MESSAGE_CHARS, request.messages().get(i).text().length()) + 32;
            if (size + len > MAX_WINDOW_CHARS && i > start) {
                out.add(new int[] {start, i});
                start = i;
                size = 0;
            }
            size += len;
        }
        if (start < request.messages().size()) out.add(new int[] {start, request.messages().size()});
        return out;
    }

    private List<ExtractedMemory> extractWindow(ExtractionRequest request, int from, int to) throws ModelHttp.ModelException {
        StringBuilder user = new StringBuilder("Conversation: ").append(request.conversationTitle()).append("\n\n");
        for (int i = from; i < to; i++) {
            ExtractionRequest.SourceMessage m = request.messages().get(i);
            String text = m.text().length() > MAX_MESSAGE_CHARS ? m.text().substring(0, MAX_MESSAGE_CHARS) : m.text();
            user.append('[').append(i).append("] ").append(m.role()).append(": ").append(text).append("\n\n");
        }
        Map<String, Object> reply = jsonReply(complete(EXTRACT_SYSTEM, user.toString(), 4_000));
        List<ExtractedMemory> out = new ArrayList<>();
        if (!(reply.get("memories") instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?>)) continue;
            Map<String, Object> m = Json.obj(o);
            MemoryKind kind;
            try {
                kind = MemoryKind.valueOf(String.valueOf(m.get("kind")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!(m.get("message") instanceof Number n)) continue;
            int index = n.intValue();
            if (index < from || index >= to) continue;
            String source = request.messages().get(index).text();
            int[] span = locate(source, Json.str(m, "quote"));
            if (span == null) continue;  // a quote that isn't in the message would make the citation a lie
            String title = Json.str(m, "title");
            if (title == null || title.isBlank()) title = source.substring(span[0], span[1]);
            List<String> rejected = new ArrayList<>();
            if (m.get("rejected") instanceof List<?> r) for (Object x : r) if (x != null && !x.toString().isBlank()) rejected.add(x.toString().strip());
            out.add(new ExtractedMemory(kind, clip(title.strip(), 300), clip(Json.str(m, "body"), 600),
                    clip(Json.str(m, "rationale"), 400), rejected, index, span[0], span[1]));
        }
        return out;
    }

    // ------------------------------------------------------------------ answers

    static final String ANSWER_SYSTEM = """
            You answer questions about a software project using only the numbered passages, which come from \
            the project's own conversations. Rules:
            - Use only what the passages say. Don't add outside knowledge.
            - After each sentence, cite the passages that support it, like [2] or [1][3].
            - If the passages don't answer the question, say so briefly and set "answerable" to false.
            - Keep the answer under 120 words, in plain language.
            - For each passage you cite, give a "quote": words copied exactly, character for character, from \
            that passage, that support your answer.
            Return only JSON, no other text: \
            {"answerable": true, "answer": "...", "citations": [{"n": 1, "quote": "..."}]}""";

    private static final Pattern CITE = Pattern.compile("\\[(\\d{1,2})]");

    @Override
    public AnswerDraft answer(AnswerRequest request) {
        if (request.contexts().isEmpty()) return fallback.answer(request);
        try {
            StringBuilder user = new StringBuilder("Question: ").append(request.question()).append("\n\nPassages:\n");
            for (AnswerRequest.Context c : request.contexts()) {
                user.append('[').append(c.n()).append("] (").append(c.conversationTitle());
                if (c.sentAt() != null) user.append(", ").append(DAY.format(c.sentAt()));
                if (c.decision()) user.append(", a decision");
                user.append(")\n").append(c.text()).append("\n\n");
            }
            Map<String, Object> reply = jsonReply(complete(ANSWER_SYSTEM, user.toString(), 1_200));
            String text = Json.str(reply, "answer");
            if (!Boolean.TRUE.equals(reply.get("answerable")) || text == null || text.isBlank()) return fallback.answer(request);

            Map<Integer, AnswerRequest.Context> byN = new LinkedHashMap<>();
            for (AnswerRequest.Context c : request.contexts()) byN.put(c.n(), c);
            List<AnswerDraft.Citation> citations = new ArrayList<>();
            Set<Integer> valid = new LinkedHashSet<>();
            if (reply.get("citations") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?>)) continue;
                    Map<String, Object> c = Json.obj(o);
                    if (!(c.get("n") instanceof Number n)) continue;
                    AnswerRequest.Context ctx = byN.get(n.intValue());
                    if (ctx == null) continue;
                    int[] span = locate(ctx.text(), Json.str(c, "quote"));
                    if (span == null) continue;
                    citations.add(new AnswerDraft.Citation(ctx.n(), ctx.chunkId(), span[0], span[1]));
                    valid.add(ctx.n());
                }
            }
            if (citations.isEmpty()) return fallback.answer(request);
            // Drop citation markers the quotes didn't back up.
            Matcher m = CITE.matcher(text);
            StringBuilder cleaned = new StringBuilder();
            while (m.find()) m.appendReplacement(cleaned, valid.contains(Integer.parseInt(m.group(1))) ? "$0" : "");
            m.appendTail(cleaned);
            return new AnswerDraft(cleaned.toString().replaceAll(" +([.,;])", "$1").strip(), citations, true);
        } catch (ModelHttp.ModelException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Answer fell back to offline: {0}", e.getMessage());
            return fallback.answer(request);
        }
    }

    // ------------------------------------------------------------------ drift

    static final String DRIFT_SYSTEM = """
            A software project made a decision. A later sentence may show the project going against it, by \
            using or adopting something the decision ruled out. Answer with one word: "yes" if the sentence goes \
            against the decision, "no" if it doesn't (for example, it only mentions the option, rejects it, or \
            is about something else).""";

    @Override
    public boolean confirmsDrift(String decision, String evidence) {
        try {
            String reply = complete(DRIFT_SYSTEM, "Decision: " + decision + "\nLater sentence: " + evidence, 5)
                    .strip().toLowerCase(Locale.ROOT);
            return !reply.startsWith("no");
        } catch (ModelHttp.ModelException | RuntimeException e) {
            return true;  // the rules already flagged it; without a model, keep the alert
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** One Messages API call; returns the text of the reply. */
    String complete(String system, String user, int maxTokens) throws ModelHttp.ModelException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        body.put("messages", List.of(Map.of("role", "user", "content", user)));
        String raw = http.post(config.baseUrl().resolve("/v1/messages"),
                Map.of("x-api-key", config.apiKey(), "anthropic-version", API_VERSION), Json.write(body));
        Map<String, Object> response = Json.obj(Json.parse(raw));
        if ("refusal".equals(response.get("stop_reason"))) throw new ModelHttp.ModelException("the model declined the request");
        StringBuilder text = new StringBuilder();
        if (response.get("content") instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> block && "text".equals(block.get("type")) && block.get("text") != null) {
                    text.append(block.get("text"));
                }
            }
        }
        if (text.isEmpty()) throw new ModelHttp.ModelException("empty reply");
        return text.toString();
    }

    /** The JSON object in a reply, tolerating code fences or a sentence around it. */
    static Map<String, Object> jsonReply(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("no JSON in the reply");
        return Json.obj(Json.parse(text.substring(start, end + 1)));
    }

    /**
     * Where {@code quote} appears in {@code source}: exactly, or ignoring case and runs of
     * whitespace. Null if it isn't there (or is too short to mean anything).
     */
    static int[] locate(String source, String quote) {
        if (source == null || quote == null) return null;
        String q = quote.strip();
        if (q.length() < 6) return null;
        int at = source.indexOf(q);
        if (at >= 0) return new int[] {at, at + q.length()};
        // Loose match: build a pattern from the quote's words, allowing any whitespace between them.
        String[] words = q.split("\\s+");
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) p.append("\\s+");
            p.append(Pattern.quote(words[i]));
        }
        Matcher m = Pattern.compile(p.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(source);
        return m.find() ? new int[] {m.start(), m.end()} : null;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "\u2026";
    }
}
