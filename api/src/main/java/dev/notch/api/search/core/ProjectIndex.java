package dev.notch.api.search.core;

import dev.notch.engine.graph.ThreadStitcher;
import dev.notch.engine.select.RandomizedSelect;
import dev.notch.engine.text.CorpusIndex;
import dev.notch.engine.text.Kmp;
import dev.notch.engine.text.RadixTree;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything search needs for one project, held in memory and rebuilt after imports:
 * <ul>
 *   <li>a {@link CorpusIndex} (suffix array) over every chunk, for exact matching</li>
 *   <li>a {@link RadixTree} of code-shaped keys and conversation titles, for {@code @} autocomplete</li>
 *   <li>a {@link ThreadStitcher} (union-find) linking conversations that share files or errors</li>
 * </ul>
 * Instances are immutable after {@link #build}, so searches never need locks; a rebuild
 * creates a new instance and swaps it in.
 */
public final class ProjectIndex {

    /** A suggestion for {@code @} autocomplete. */
    public record Suggestion(String key, double weight) {
    }

    /** Index size and build cost, shown in the UI and the Lab page. */
    public record Stats(int chunks, int conversations, int indexedChars, int autocompleteKeys,
                        int threads, long buildMillis, Instant builtAt) {
    }

    static final int MAX_COUNT_PER_TERM = 5;  // keeps one repetitive chunk from dominating
    static final double PHRASE_BOOST = 2.0;
    static final double EXPANSION_WEIGHT = 0.5;

    private final Map<UUID, IndexedChunk> byId;
    private final List<IndexedChunk> chunks;
    private final CorpusIndex corpus;
    private final RadixTree keys;
    private final Map<String, String> displayKey;  // lower-case key -> form shown to the user
    private final ThreadStitcher threads;
    private final Map<UUID, String> conversationTitles;
    private final Stats stats;

    private ProjectIndex(Map<UUID, IndexedChunk> byId, List<IndexedChunk> chunks, CorpusIndex corpus,
                         RadixTree keys, Map<String, String> displayKey, ThreadStitcher threads,
                         Map<UUID, String> conversationTitles, Stats stats) {
        this.byId = byId;
        this.chunks = chunks;
        this.corpus = corpus;
        this.keys = keys;
        this.displayKey = displayKey;
        this.threads = threads;
        this.conversationTitles = conversationTitles;
        this.stats = stats;
    }

    public static ProjectIndex empty() {
        return build(List.of(), Instant.now(), new RandomizedSelect());
    }

    public static ProjectIndex build(List<IndexedChunk> input, Instant now, RandomizedSelect select) {
        long start = System.nanoTime();
        List<IndexedChunk> chunks = List.copyOf(input);
        Map<UUID, IndexedChunk> byId = new HashMap<>();
        List<CorpusIndex.Doc> docs = new ArrayList<>(chunks.size());
        for (IndexedChunk c : chunks) {
            if (byId.putIfAbsent(c.chunkId(), c) != null) {
                throw new IllegalArgumentException("duplicate chunk " + c.chunkId());
            }
            docs.add(new CorpusIndex.Doc(c.chunkId().toString(), c.text()));
        }
        CorpusIndex corpus = CorpusIndex.build(docs);

        // Autocomplete: weight = occurrences + a recency bonus of up to 2.
        Map<String, Double> weights = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        Map<UUID, String> titles = new LinkedHashMap<>();
        Map<UUID, Set<String>> linkKeysByConversation = new LinkedHashMap<>();
        for (IndexedChunk c : chunks) {
            double recency = 2 * Math.pow(0.5, Math.max(0, Duration.between(c.sentAt(), now).toDays()) / 30.0);
            for (String k : KeyExtractor.autocompleteKeys(c.text())) {
                String low = k.toLowerCase(Locale.ROOT);
                weights.merge(low, 1.0, Double::sum);
                weights.merge(low, recency / 10, Double::sum);
                display.putIfAbsent(low, k);
            }
            if (titles.putIfAbsent(c.conversationId(), c.conversationTitle()) == null && c.conversationTitle() != null) {
                String low = c.conversationTitle().toLowerCase(Locale.ROOT);
                weights.merge(low, 1.0 + recency, Double::sum);
                display.putIfAbsent(low, c.conversationTitle());
            }
            linkKeysByConversation.computeIfAbsent(c.conversationId(), id -> new java.util.LinkedHashSet<>())
                    .addAll(KeyExtractor.linkKeys(c.text()));
        }
        RadixTree tree = new RadixTree(select);
        weights.forEach((k, w) -> {
            if (!k.isBlank()) tree.insert(k, w);
        });

        ThreadStitcher stitcher = stitch(linkKeysByConversation);
        long millis = (System.nanoTime() - start) / 1_000_000;
        Stats stats = new Stats(chunks.size(), titles.size(), corpus.indexedLength(), tree.size(),
                stitcher.threadCount(), millis, now);
        return new ProjectIndex(byId, chunks, corpus, tree, display, stitcher, titles, stats);
    }

    /**
     * Links conversations sharing a key. Keys present in more than half of all conversations
     * (and in more than three) say nothing specific, so they are ignored.
     */
    private static ThreadStitcher stitch(Map<UUID, Set<String>> keysByConversation) {
        Map<String, Integer> df = new HashMap<>();
        for (Set<String> ks : keysByConversation.values()) {
            for (String k : ks) df.merge(k.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        int maxDf = Math.max(3, keysByConversation.size() / 2);
        Set<String> ignored = new java.util.HashSet<>();
        df.forEach((k, n) -> {
            if (n > maxDf) ignored.add(k);
        });
        ThreadStitcher stitcher = new ThreadStitcher(ignored);
        keysByConversation.forEach((conv, ks) -> stitcher.addItem(conv.toString(), ks));
        return stitcher;
    }

    /**
     * Exact-match score per chunk: for each pattern, occurrences (capped) times an inverse
     * document frequency weight, so rare terms such as error codes count more than common
     * words. Phrases count double, expansions half. Scores are scaled to integers for the ranker.
     */
    public Map<UUID, Integer> exactScores(QueryTerms q) {
        Map<UUID, Double> raw = new HashMap<>();
        int n = chunks.size();
        if (n == 0) return Map.of();
        List<String> all = new ArrayList<>(q.patterns());
        all.addAll(q.expansions());
        for (String pattern : all) {
            if (pattern.isBlank()) continue;
            List<CorpusIndex.DocMatches> hits = corpus.search(pattern, 0);
            if (hits.isEmpty()) continue;
            double idf = Math.log(1 + (double) n / hits.size());
            double boost = q.phrases().contains(pattern) ? PHRASE_BOOST
                    : q.expansions().contains(pattern) && !q.terms().contains(pattern) ? EXPANSION_WEIGHT : 1.0;
            for (CorpusIndex.DocMatches m : hits) {
                double add = Math.min(m.count(), MAX_COUNT_PER_TERM) * idf * boost;
                raw.merge(UUID.fromString(m.docId()), add, Double::sum);
            }
        }
        Map<UUID, Integer> out = new HashMap<>(raw.size());
        raw.forEach((id, s) -> out.put(id, Math.max(1, (int) Math.round(s * 10))));
        return out;
    }

    /** Whether a code-shaped key (a path, an error, an identifier) appears anywhere, via the radix tree. */
    public boolean hasKey(String key) {
        return key != null && keys.contains(key.toLowerCase(Locale.ROOT));
    }

    /** Every chunk containing {@code term} (case-insensitive), via the suffix array. */
    public List<IndexedChunk> chunksContaining(String term) {
        if (term == null || term.isBlank() || chunks.isEmpty()) return List.of();
        List<IndexedChunk> out = new ArrayList<>();
        for (CorpusIndex.DocMatches m : corpus.search(term, 0)) out.add(byId.get(UUID.fromString(m.docId())));
        return out;
    }

    /** Chunks holding an active decision, optionally only those since a time. */
    public List<IndexedChunk> decisionChunks(Instant since) {
        List<IndexedChunk> out = new ArrayList<>();
        for (IndexedChunk c : chunks) {
            if (c.decision() && (since == null || !c.sentAt().isBefore(since))) out.add(c);
        }
        return out;
    }

    /** Character spans {start, end} of every query pattern in {@code text}, merged, via KMP. */
    public static List<int[]> highlights(String text, QueryTerms q) {
        List<int[]> spans = new ArrayList<>();
        List<String> all = new ArrayList<>(q.patterns());
        all.addAll(q.expansions());
        for (String p : all) {
            if (p.isBlank()) continue;
            for (int at : Kmp.findAll(text, p, true)) spans.add(new int[] {at, at + p.length()});
        }
        spans.sort(Comparator.comparingInt((int[] s) -> s[0]).thenComparingInt(s -> s[1]));
        List<int[]> merged = new ArrayList<>();
        for (int[] s : spans) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], s[1]);
            } else {
                merged.add(new int[] {s[0], s[1]});
            }
        }
        return merged;
    }

    public List<Suggestion> autocomplete(String prefix, int k) {
        String low = prefix.strip().toLowerCase(Locale.ROOT);
        List<Suggestion> out = new ArrayList<>();
        for (RadixTree.Entry e : keys.complete(low, k)) {
            out.add(new Suggestion(displayKey.getOrDefault(e.key(), e.key()), e.weight()));
        }
        return out;
    }

    /** The thread (earliest linked conversation) that a conversation belongs to. */
    public UUID threadOf(UUID conversationId) {
        return UUID.fromString(threads.threadOf(conversationId.toString()));
    }

    /** Threads with more than one conversation, largest first. */
    public List<List<UUID>> linkedThreads() {
        List<List<UUID>> out = new ArrayList<>();
        for (List<String> group : threads.threads().values()) {
            if (group.size() > 1) out.add(group.stream().map(UUID::fromString).toList());
        }
        out.sort(Comparator.comparingInt((List<UUID> g) -> g.size()).reversed());
        return out;
    }

    public IndexedChunk chunk(UUID id) {
        return byId.get(id);
    }

    public String conversationTitle(UUID conversationId) {
        return conversationTitles.get(conversationId);
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public Stats stats() {
        return stats;
    }
}
