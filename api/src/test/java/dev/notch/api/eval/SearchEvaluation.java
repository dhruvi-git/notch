package dev.notch.api.eval;

import dev.notch.api.ingest.core.Chunk;
import dev.notch.api.ingest.core.Chunker;
import dev.notch.api.llm.ExtractedMemory;
import dev.notch.api.llm.ExtractionRequest;
import dev.notch.api.llm.HashingEmbeddingClient;
import dev.notch.api.llm.HeuristicMemoryExtractor;
import dev.notch.api.llm.MemoryKind;
import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.search.core.ProjectIndex;
import dev.notch.api.search.core.QueryTerms;
import dev.notch.api.search.core.SearchPipeline;
import dev.notch.engine.rank.HybridRanker;
import dev.notch.engine.select.RandomizedSelect;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Measures search quality on a labelled set: 50 questions over a fictional startup's chats,
 * each with the messages that answer it.
 *
 * <p>Every chat goes through the same steps as a real import: the chunker, the heuristic
 * decision extractor (to mark decision passages), the project index and {@link SearchPipeline}.
 * Vector search uses the same offline embeddings and cut-offs as the app, with an exact
 * in-memory nearest-neighbour search standing in for pgvector.
 *
 * <p>Three modes are compared: hybrid (what the app does), exact only (suffix array) and
 * vector only. Results are scored per message: a passage counts for the message it came from.
 *
 * <p>Run: {@code mvn -q -pl api test -Dtest=SearchEvaluationTest} (writes docs/evaluation.md
 * when {@code -Dnotch.eval.out=../docs/evaluation.md} is set).
 */
public final class SearchEvaluation {

    /** The evaluation's "today": message dates are counted back from here. */
    public static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    static final int K = 10;

    public record Message(String id, String conversationId, String conversationTitle, String role, String text, Instant sentAt) {
    }

    public record Question(String id, String category, String text, Set<String> relevant) {
    }

    /** Where the first relevant message ranked (0 if not in the top K), and how many relevant ones were found. */
    public record Outcome(Question question, int firstRank, int foundInTopK, List<String> topMessages) {
    }

    public record Scores(double hitAt1, double hitAt5, double mrr, double recallAtK, double avgMillis) {
    }

    public record Report(Map<SearchPipeline.Mode, List<Outcome>> outcomes, Map<SearchPipeline.Mode, Scores> overall,
                         Map<SearchPipeline.Mode, Map<String, Scores>> byCategory, int messages, int chunks) {
    }

    private final List<Message> messages;
    private final List<Question> questions;
    private final Map<UUID, String> messageOfChunk = new HashMap<>();
    private final Map<UUID, float[]> vectors = new LinkedHashMap<>();
    private final dev.notch.api.llm.EmbeddingClient embeddings;
    private final ProjectIndex index;

    public SearchEvaluation(List<Message> messages, List<Question> questions) {
        this(messages, questions, new HashingEmbeddingClient(1024));
    }

    /** With any embedding client: the offline one by default, or a real model such as Voyage. */
    public SearchEvaluation(List<Message> messages, List<Question> questions, dev.notch.api.llm.EmbeddingClient embeddings) {
        this.messages = messages;
        this.questions = questions;
        this.embeddings = embeddings;
        this.index = buildIndex();
    }

    // ------------------------------------------------------------------ loading

    public static SearchEvaluation fromResources() throws IOException {
        return new SearchEvaluation(parseCorpus(resource("eval/corpus.txt")), parseQuestions(resource("eval/questions.tsv")));
    }

    public static SearchEvaluation fromResources(dev.notch.api.llm.EmbeddingClient embeddings) throws IOException {
        return new SearchEvaluation(parseCorpus(resource("eval/corpus.txt")), parseQuestions(resource("eval/questions.tsv")), embeddings);
    }

    /** Voyage embeddings when VOYAGE_API_KEY is set, else null. */
    public static dev.notch.api.llm.EmbeddingClient voyageFromEnvironment() {
        String key = System.getenv("VOYAGE_API_KEY");
        if (key == null || key.isBlank()) return null;
        String model = System.getenv().getOrDefault("NOTCH_EMBEDDING_MODEL", "voyage-4-lite");
        return new dev.notch.api.llm.VoyageEmbeddingClient(new dev.notch.api.llm.VoyageEmbeddingClient.Config(key, model, 1024, null, null));
    }

    static String resource(String name) throws IOException {
        try (InputStream in = SearchEvaluation.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("Missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<Message> parseCorpus(String text) {
        List<Message> out = new ArrayList<>();
        String conv = null;
        String title = null;
        Instant start = null;
        int n = 0;
        for (String line : text.split("\\R")) {
            if (line.startsWith("#") && !line.startsWith("## ")) continue;
            if (line.startsWith("## ")) {
                String[] parts = line.substring(3).split("\\|");
                conv = parts[0].strip();
                title = parts[1].strip();
                start = NOW.minus(Duration.ofDays(Long.parseLong(parts[2].strip())));
                n = 0;
            } else if (line.startsWith("user: ") || line.startsWith("assistant: ")) {
                int colon = line.indexOf(':');
                n++;
                out.add(new Message(conv + "m" + n, conv, title, line.substring(0, colon), line.substring(colon + 2).strip(),
                        start.plus(Duration.ofMinutes(n))));
            }
        }
        return out;
    }

    static List<Question> parseQuestions(String text) {
        List<Question> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            out.add(new Question(f[0], f[1], f[2], new LinkedHashSet<>(List.of(f[3].split(",")))));
        }
        return out;
    }

    // ------------------------------------------------------------------ indexing, as an import does

    private ProjectIndex buildIndex() {
        Map<String, List<Message>> byConversation = new LinkedHashMap<>();
        for (Message m : messages) byConversation.computeIfAbsent(m.conversationId(), k -> new ArrayList<>()).add(m);
        HeuristicMemoryExtractor extractor = new HeuristicMemoryExtractor();
        Chunker chunker = new Chunker(1200);
        List<IndexedChunk> chunks = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (List<Message> conv : byConversation.values()) {
            List<ExtractionRequest.SourceMessage> source = conv.stream()
                    .map(m -> new ExtractionRequest.SourceMessage(m.role(), m.text())).toList();
            Map<Integer, List<int[]>> decisionSpans = new HashMap<>();
            for (ExtractedMemory mem : extractor.extractMemories(new ExtractionRequest(conv.get(0).conversationTitle(), source))) {
                if (mem.kind() == MemoryKind.DECISION) {
                    decisionSpans.computeIfAbsent(mem.messageIndex(), k -> new ArrayList<>()).add(new int[] {mem.start(), mem.end()});
                }
            }
            UUID convId = UUID.nameUUIDFromBytes(conv.get(0).conversationId().getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < conv.size(); i++) {
                Message m = conv.get(i);
                UUID messageId = UUID.nameUUIDFromBytes(m.id().getBytes(StandardCharsets.UTF_8));
                for (Chunk c : chunker.split(m.text())) {
                    boolean decision = false;
                    for (int[] s : decisionSpans.getOrDefault(i, List.of())) decision |= s[0] < c.end() && c.start() < s[1];
                    UUID chunkId = UUID.nameUUIDFromBytes((m.id() + ":" + c.start()).getBytes(StandardCharsets.UTF_8));
                    chunks.add(new IndexedChunk(chunkId, messageId, convId, m.conversationTitle(), m.role(), m.sentAt(),
                            c.text(), c.estimatedTokens(), decision));
                    messageOfChunk.put(chunkId, m.id());
                    texts.add(c.text());
                }
            }
        }
        // One batched call for every passage, as an import would make.
        List<float[]> vs = embeddings.embed(texts);
        for (int i = 0; i < chunks.size(); i++) vectors.put(chunks.get(i).chunkId(), vs.get(i));
        return ProjectIndex.build(chunks, NOW, new RandomizedSelect(1));
    }

    /** Vector search: exact cosine nearest neighbours, with the app's limits. */
    private Map<UUID, Double> nearest(String query) {
        float[] q = embeddings.embedQuery(query);
        if (q == null) return Map.of();
        List<Map.Entry<UUID, Double>> sims = new ArrayList<>();
        for (Map.Entry<UUID, float[]> e : vectors.entrySet()) {
            if (e.getValue() == null) continue;
            double dot = 0;
            for (int i = 0; i < q.length; i++) dot += q[i] * e.getValue()[i];
            sims.add(Map.entry(e.getKey(), dot));
        }
        sims.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        Map<UUID, Double> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> e : sims.subList(0, Math.min(SearchPipeline.SEMANTIC_CANDIDATES, sims.size()))) {
            if (e.getValue() >= embeddings.minSimilarity()) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ------------------------------------------------------------------ running

    public Report run() {
        Map<SearchPipeline.Mode, List<Outcome>> outcomes = new LinkedHashMap<>();
        Map<SearchPipeline.Mode, Scores> overall = new LinkedHashMap<>();
        Map<SearchPipeline.Mode, Map<String, Scores>> byCategory = new LinkedHashMap<>();
        SearchPipeline pipeline = new SearchPipeline(new RandomizedSelect(1));
        for (SearchPipeline.Mode mode : SearchPipeline.Mode.values()) {
            List<Outcome> list = new ArrayList<>();
            long totalNanos = 0;
            for (Question q : questions) {
                long t = System.nanoTime();
                QueryTerms terms = QueryTerms.parse(q.text(), NOW);
                Map<UUID, Double> semantic = mode == SearchPipeline.Mode.EXACT_ONLY ? Map.of() : nearest(q.text());
                SearchPipeline.Result r = pipeline.rank(index, terms, semantic, terms.since(), NOW, K * 3, mode);
                totalNanos += System.nanoTime() - t;
                list.add(score(q, r.ranked()));
            }
            outcomes.put(mode, list);
            overall.put(mode, summarise(list, totalNanos / 1e6 / questions.size()));
            Map<String, List<Outcome>> cats = new LinkedHashMap<>();
            for (Outcome o : list) cats.computeIfAbsent(o.question().category(), k -> new ArrayList<>()).add(o);
            Map<String, Scores> catScores = new LinkedHashMap<>();
            cats.forEach((c, l) -> catScores.put(c, summarise(l, 0)));
            byCategory.put(mode, catScores);
        }
        return new Report(outcomes, overall, byCategory, messages.size(), vectors.size());
    }

    /** Collapses passages to the messages they came from, in rank order, and finds the answers. */
    private Outcome score(Question q, List<HybridRanker.Ranked> ranked) {
        List<String> top = new ArrayList<>();
        for (HybridRanker.Ranked r : ranked) {
            String m = messageOfChunk.get(UUID.fromString(r.candidate().chunkId()));
            if (!top.contains(m)) top.add(m);
            if (top.size() == K) break;
        }
        int first = 0;
        int found = 0;
        for (int i = 0; i < top.size(); i++) {
            if (q.relevant().contains(top.get(i))) {
                found++;
                if (first == 0) first = i + 1;
            }
        }
        return new Outcome(q, first, found, top);
    }

    private static Scores summarise(List<Outcome> list, double avgMillis) {
        double h1 = 0, h5 = 0, mrr = 0, recall = 0;
        for (Outcome o : list) {
            if (o.firstRank() == 1) h1++;
            if (o.firstRank() >= 1 && o.firstRank() <= 5) h5++;
            if (o.firstRank() >= 1) mrr += 1.0 / o.firstRank();
            recall += (double) o.foundInTopK() / o.question().relevant().size();
        }
        int n = list.size();
        return new Scores(h1 / n, h5 / n, mrr / n, recall / n, avgMillis);
    }

    // ------------------------------------------------------------------ report

    static final Map<SearchPipeline.Mode, String> MODE_NAMES = Map.of(
            SearchPipeline.Mode.HYBRID, "Hybrid (what Notch does)",
            SearchPipeline.Mode.EXACT_ONLY, "Exact only (suffix array)",
            SearchPipeline.Mode.SEMANTIC_ONLY, "Vector only (offline embeddings)");

    public static String markdown(Report r, List<Question> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Search evaluation\n\n");
        sb.append("Generated by `SearchEvaluation` (api/src/test/java/dev/notch/api/eval). ")
                .append(questions.size()).append(" labelled questions over ").append(r.messages()).append(" messages (")
                .append(r.chunks()).append(" passages) from a fictional startup's chats: `api/src/test/resources/eval/`.\n\n");
        sb.append("Each chat goes through the real import steps (chunking, decision extraction, indexing) and the app's ")
                .append("ranking code (`SearchPipeline`). Vector search uses the app's offline word-hashing embeddings, with an ")
                .append("exact nearest-neighbour search in place of pgvector. A result counts for the message it came from.\n\n");
        sb.append("## Overall\n\n| Mode | Hit@1 | Hit@5 | MRR@10 | Recall@10 | ms per query |\n|---|---|---|---|---|---|\n");
        r.overall().forEach((mode, s) -> sb.append(String.format(Locale.ROOT, "| %s | %.2f | %.2f | %.3f | %.2f | %.2f |%n",
                MODE_NAMES.get(mode), s.hitAt1(), s.hitAt5(), s.mrr(), s.recallAtK(), s.avgMillis())));
        sb.append("\nHit@k: share of questions with an answer in the top k. MRR@10: mean of 1/rank of the first answer ")
                .append("(0 if none in the top 10). Recall@10: share of each question's answers found in the top 10.\n\n");
        sb.append("## MRR@10 by kind of question\n\n| Kind | Questions |");
        for (SearchPipeline.Mode m : r.byCategory().keySet()) sb.append(' ').append(MODE_NAMES.get(m)).append(" |");
        sb.append("\n|---|---|");
        r.byCategory().keySet().forEach(m -> sb.append("---|"));
        sb.append('\n');
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Question q : questions) counts.merge(q.category(), 1L, Long::sum);
        for (String cat : counts.keySet()) {
            sb.append("| ").append(cat).append(" | ").append(counts.get(cat)).append(" |");
            for (SearchPipeline.Mode m : r.byCategory().keySet()) {
                sb.append(String.format(Locale.ROOT, " %.3f |", r.byCategory().get(m).get(cat).mrr()));
            }
            sb.append('\n');
        }
        sb.append("\n## Every question\n\nRank of the first answer in each mode (\u2013 means not in the top 10).\n\n")
                .append("| Id | Kind | Question | Hybrid | Exact | Vector |\n|---|---|---|---|---|---|\n");
        List<Outcome> hy = r.outcomes().get(SearchPipeline.Mode.HYBRID);
        List<Outcome> ex = r.outcomes().get(SearchPipeline.Mode.EXACT_ONLY);
        List<Outcome> ve = r.outcomes().get(SearchPipeline.Mode.SEMANTIC_ONLY);
        for (int i = 0; i < hy.size(); i++) {
            Question q = hy.get(i).question();
            sb.append("| ").append(q.id()).append(" | ").append(q.category()).append(" | ").append(q.text().replace("|", "\\|"))
                    .append(" | ").append(rank(hy.get(i))).append(" | ").append(rank(ex.get(i))).append(" | ").append(rank(ve.get(i)))
                    .append(" |\n");
        }
        sb.append("\n## What the first run found\n\n")
                .append("The evaluation was first run before any search changes. Its failures pointed to two bugs and one ")
                .append("missing case. Each fix is a general mechanism with its own test (`QueryTermsCategoryTest`), not a ")
                .append("weight tuned to these questions:\n\n")
                .append("- **Expansions never matched stemmed words.** Query words are stemmed (\"authentication\" to \"authentic\", ")
                .append("\"hosting\" to \"host\") but the expansion table was looked up with unstemmed keys, so most entries never ")
                .append("matched. The table is now keyed by stem as well.\n")
                .append("- **Words for a kind of tool didn't reach the tools.** \"What did we decide about styling?\" couldn't find ")
                .append("\"We chose CSS modules over Tailwind\". Category words now expand to every tool Notch knows in that ")
                .append("category, from the same catalogue the guard uses.\n")
                .append("- **Time-only questions had no candidates.** \"What did we work on yesterday?\" matched no words. When ")
                .append("nothing matches, the passages from the time window are now the candidates, ranked by recency.\n\n")
                .append("| MRR@10 | First run | Now |\n|---|---|---|\n");
        double[] first = {0.719, 0.675, 0.345};
        int i0 = 0;
        for (SearchPipeline.Mode m : SearchPipeline.Mode.values()) {
            sb.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f |%n", MODE_NAMES.get(m), first[i0++], r.overall().get(m).mrr()));
        }
        sb.append(String.format(Locale.ROOT, "| Hybrid, decision questions only | 0.533 | %.3f |%n",
                r.byCategory().get(SearchPipeline.Mode.HYBRID).get("decisions").mrr()));
        sb.append("\nBecause the first run's failures guided the fixes, the current numbers are somewhat optimistic for these ")
                .append("50 questions. A fresh set written by someone else would be the fairer test.\n");
        sb.append("\n## Limits of this evaluation\n\n")
                .append("- The same person wrote the chats and the questions, which tends to favour word matching.\n")
                .append("- The corpus is small (").append(r.messages()).append(" messages); real projects have thousands of passages, ")
                .append("where ranking matters more.\n")
                .append("- Vector search here is the offline word-hashing embedding, which shares words rather than meaning. ")
                .append("With a real embedding model, paraphrased questions should do better; the harness takes any embedding client.\n");
        return sb.toString();
    }

    /** Inserts the real-embeddings section before the per-question table. */
    static String withComparison(String md, Report offline, Report real, String model) {
        int at = md.indexOf("\n## Every question");
        String section = comparison(offline, real, model);
        return at < 0 ? md + section : md.substring(0, at) + section + md.substring(at);
    }

    private static String rank(Outcome o) {
        return o.firstRank() == 0 ? "\u2013" : String.valueOf(o.firstRank());
    }

    /** A section comparing a run with real embeddings against the offline run. */
    public static String comparison(Report offline, Report real, String model) {
        StringBuilder sb = new StringBuilder("\n## With real embeddings (").append(model).append(")\n\n")
                .append("The same questions, with vector search using ").append(model)
                .append(" instead of the offline word hashing. Everything else is identical.\n\n")
                .append("| Mode | Hit@1 | Hit@5 | MRR@10 | Recall@10 | MRR@10 offline |\n|---|---|---|---|---|---|\n");
        for (SearchPipeline.Mode m : SearchPipeline.Mode.values()) {
            if (m == SearchPipeline.Mode.EXACT_ONLY) continue;  // no embeddings involved
            Scores s = real.overall().get(m);
            sb.append(String.format(Locale.ROOT, "| %s | %.2f | %.2f | %.3f | %.2f | %.3f |%n",
                    MODE_NAMES.get(m).replace("offline embeddings", model), s.hitAt1(), s.hitAt5(), s.mrr(), s.recallAtK(),
                    offline.overall().get(m).mrr()));
        }
        sb.append("\n| Kind | Hybrid, offline | Hybrid, ").append(model).append(" | Vector, offline | Vector, ").append(model)
                .append(" |\n|---|---|---|---|---|\n");
        for (String cat : offline.byCategory().get(SearchPipeline.Mode.HYBRID).keySet()) {
            sb.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.3f |%n", cat,
                    offline.byCategory().get(SearchPipeline.Mode.HYBRID).get(cat).mrr(),
                    real.byCategory().get(SearchPipeline.Mode.HYBRID).get(cat).mrr(),
                    offline.byCategory().get(SearchPipeline.Mode.SEMANTIC_ONLY).get(cat).mrr(),
                    real.byCategory().get(SearchPipeline.Mode.SEMANTIC_ONLY).get(cat).mrr()));
        }
        return sb.toString();
    }

    public List<Question> questions() {
        return questions;
    }

    public List<Message> messages() {
        return messages;
    }

    public static void main(String[] args) throws IOException {
        SearchEvaluation e = fromResources();
        Report r = e.run();
        String md = markdown(r, e.questions());
        dev.notch.api.llm.EmbeddingClient voyage = voyageFromEnvironment();
        if (voyage != null) md = withComparison(md, r, fromResources(voyage).run(), voyage.name());
        if (args.length > 0) Files.writeString(Path.of(args[0]), md, StandardCharsets.UTF_8);
        System.out.println(md);
    }
}
