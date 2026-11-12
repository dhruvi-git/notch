package dev.notch.api.search.core;

import dev.notch.engine.rank.HybridRanker;
import dev.notch.engine.select.RandomizedSelect;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a query into ranked passages, given a project's index and its vector-search
 * neighbours. Plain Java, so the API and the evaluation run exactly the same ranking.
 *
 * <p>Modes exist for evaluation: {@link Mode#HYBRID} is what the app does; the other two keep
 * one source of candidates and rank by that signal alone, as baselines.
 */
public final class SearchPipeline {

    public enum Mode {
        /** Suffix-array and vector candidates, ranked on exact, semantic, recency and decision signals. */
        HYBRID,
        /** Suffix-array candidates ranked by exact-match score only. */
        EXACT_ONLY,
        /** Vector-search candidates ranked by similarity only. */
        SEMANTIC_ONLY
    }

    public record Result(List<HybridRanker.Ranked> ranked, int exactCandidates, int semanticCandidates) {
    }

    public static final int EXACT_CANDIDATES = 200;
    /** Nearest neighbours asked of vector search. */
    public static final int SEMANTIC_CANDIDATES = 50;
    /**
     * Neighbours below this cosine similarity are dropped: vector search always returns the k
     * nearest chunks, however unrelated. Tuned for the offline embeddings.
     */
    public static final double SEMANTIC_MIN = 0.3;
    public static final Duration RECENCY_HALF_LIFE = Duration.ofDays(14);
    /** "What did we decide…" questions lean on decisions as much as on matching words. */
    public static final HybridRanker.Weights DECISION_WEIGHTS = new HybridRanker.Weights(0.30, 0.30, 0.10, 0.30);
    static final HybridRanker.Weights EXACT_ONLY = new HybridRanker.Weights(1, 0, 0, 0);
    static final HybridRanker.Weights SEMANTIC_ONLY = new HybridRanker.Weights(0, 1, 0, 0);

    private final RandomizedSelect select;

    public SearchPipeline(RandomizedSelect select) {
        this.select = select;
    }

    public Result rank(ProjectIndex idx, QueryTerms q, Map<UUID, Double> semantic, Instant since, Instant now, int k, Mode mode) {
        Map<UUID, Integer> exact = mode == Mode.SEMANTIC_ONLY ? Map.of() : idx.exactScores(q);
        Map<UUID, Double> near = mode == Mode.EXACT_ONLY ? Map.of() : semantic;

        // Exact candidates, capped to the best few hundred with randomized selection.
        List<UUID> exactIds = exact.isEmpty() ? List.of()
                : select.topK(new ArrayList<>(exact.keySet()), EXACT_CANDIDATES, Comparator.comparingInt(exact::get));

        Set<UUID> ids = new LinkedHashSet<>(exactIds);
        ids.addAll(near.keySet());
        if (mode == Mode.HYBRID && q.isEmpty() && q.decisionIntent()) {
            // "What did we decide last week?" has no topic words: every decision is a candidate.
            for (IndexedChunk c : idx.decisionChunks(since)) ids.add(c.chunkId());
        } else if (mode == Mode.HYBRID && since != null && exactIds.isEmpty()) {
            // "What did we work on yesterday?": nothing matches the words, so the window itself is the answer.
            for (IndexedChunk c : idx.chunksSince(since)) ids.add(c.chunkId());
        }

        List<HybridRanker.Candidate> candidates = new ArrayList<>();
        for (UUID id : ids) {
            IndexedChunk c = idx.chunk(id);
            if (c == null) continue;  // embedded after the index was built; picked up on rebuild
            if (since != null && c.sentAt().isBefore(since)) continue;
            candidates.add(new HybridRanker.Candidate(id.toString(), exact.getOrDefault(id, 0),
                    near.getOrDefault(id, 0.0), c.sentAt(), c.decision(), c.tokens()));
        }

        HybridRanker.Weights weights = switch (mode) {
            case HYBRID -> q.decisionIntent() ? DECISION_WEIGHTS : HybridRanker.Weights.defaults();
            case EXACT_ONLY -> EXACT_ONLY;
            case SEMANTIC_ONLY -> SEMANTIC_ONLY;
        };
        List<HybridRanker.Ranked> ranked = candidates.isEmpty() ? List.of()
                : new HybridRanker(weights, RECENCY_HALF_LIFE, select).rank(candidates, now, k);
        return new Result(ranked, exactIds.size(), near.size());
    }
}
