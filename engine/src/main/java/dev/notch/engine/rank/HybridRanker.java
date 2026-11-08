package dev.notch.engine.rank;

import dev.notch.engine.select.RandomizedSelect;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fuses retrieval signals into one score and selects the best chunks.
 *
 * <p>Signals, each scaled to [0, 1]:
 * <ul>
 *   <li><b>exact</b>: suffix-array match count, divided by the best count in this candidate set</li>
 *   <li><b>semantic</b>: cosine similarity from the vector index, clamped to [0, 1]</li>
 *   <li><b>recency</b>: exponential decay with a configurable half-life</li>
 *   <li><b>decision</b>: 1 when the chunk is a recorded decision, else 0</li>
 * </ul>
 * The score is the weighted sum. The top k are chosen with randomized selection, and each
 * result carries its per-signal contributions for the "why this result" panel.
 */
public final class HybridRanker {

    public record Weights(double exact, double semantic, double recency, double decision) {
        public Weights {
            for (double w : new double[] {exact, semantic, recency, decision}) {
                if (!(w >= 0) || Double.isInfinite(w)) {
                    throw new IllegalArgumentException("weights must be finite and >= 0");
                }
            }
            if (exact + semantic + recency + decision == 0) {
                throw new IllegalArgumentException("at least one weight must be positive");
            }
        }

        public static Weights defaults() {
            return new Weights(0.40, 0.35, 0.10, 0.15);
        }
    }

    /** A retrieved chunk before ranking. */
    public record Candidate(String chunkId, int exactMatches, double semanticSimilarity,
                            Instant createdAt, boolean decision, int tokens) {
        public Candidate {
            Objects.requireNonNull(chunkId, "chunkId");
            Objects.requireNonNull(createdAt, "createdAt");
            if (exactMatches < 0) throw new IllegalArgumentException("exactMatches must be >= 0");
            if (tokens < 0) throw new IllegalArgumentException("tokens must be >= 0");
        }
    }

    /** A ranked chunk with its score and how each signal contributed to it. */
    public record Ranked(Candidate candidate, double score, Map<String, Double> contributions) {
    }

    private final Weights weights;
    private final Duration halfLife;
    private final RandomizedSelect select;

    public HybridRanker(Weights weights, Duration halfLife, RandomizedSelect select) {
        this.weights = Objects.requireNonNull(weights, "weights");
        this.halfLife = Objects.requireNonNull(halfLife, "halfLife");
        if (halfLife.isNegative() || halfLife.isZero()) {
            throw new IllegalArgumentException("halfLife must be positive");
        }
        this.select = Objects.requireNonNull(select, "select");
    }

    public static HybridRanker withDefaults() {
        return new HybridRanker(Weights.defaults(), Duration.ofDays(14), new RandomizedSelect());
    }

    /** Scores every candidate and returns the best {@code k}, highest first. */
    public List<Ranked> rank(List<Candidate> candidates, Instant now, int k) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(now, "now");
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        if (candidates.isEmpty()) return List.of();

        int maxExact = 0;
        for (Candidate c : candidates) maxExact = Math.max(maxExact, c.exactMatches());

        List<Ranked> scored = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) scored.add(score(c, maxExact, now));
        Comparator<Ranked> order = Comparator.comparingDouble(Ranked::score)
                .thenComparing(r -> r.candidate().chunkId(), Comparator.reverseOrder());
        return select.topK(scored, k, order);
    }

    private Ranked score(Candidate c, int maxExact, Instant now) {
        double exact = maxExact == 0 ? 0 : (double) c.exactMatches() / maxExact;
        double semantic = Math.max(0, Math.min(1, c.semanticSimilarity()));
        double ageDays = Math.max(0, Duration.between(c.createdAt(), now).toMillis()) / 86_400_000.0;
        double halfLifeDays = halfLife.toMillis() / 86_400_000.0;
        double recency = Math.pow(0.5, ageDays / halfLifeDays);
        double decision = c.decision() ? 1 : 0;

        Map<String, Double> parts = new LinkedHashMap<>();
        parts.put("exact", weights.exact() * exact);
        parts.put("semantic", weights.semantic() * semantic);
        parts.put("recency", weights.recency() * recency);
        parts.put("decision", weights.decision() * decision);
        double total = 0;
        for (double v : parts.values()) total += v;
        return new Ranked(c, total, Map.copyOf(parts));
    }

    /**
     * Keeps ranked chunks, best first, while they fit in {@code tokenBudget}. A chunk too
     * large for the remaining budget is skipped so smaller, lower-ranked chunks can still fit.
     */
    public static List<Ranked> fitToBudget(List<Ranked> ranked, int tokenBudget) {
        Objects.requireNonNull(ranked, "ranked");
        if (tokenBudget < 0) throw new IllegalArgumentException("tokenBudget must be >= 0");
        List<Ranked> out = new ArrayList<>();
        int used = 0;
        for (Ranked r : ranked) {
            int t = r.candidate().tokens();
            if (used + t <= tokenBudget) {
                out.add(r);
                used += t;
            }
        }
        return out;
    }
}
