package dev.notch.engine.rank;

import dev.notch.engine.rank.HybridRanker.Candidate;
import dev.notch.engine.rank.HybridRanker.Ranked;
import dev.notch.engine.rank.HybridRanker.Weights;
import dev.notch.engine.select.RandomizedSelect;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HybridRankerTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    private static HybridRanker ranker(Weights w) {
        return new HybridRanker(w, Duration.ofDays(14), new RandomizedSelect(1));
    }

    private static Candidate c(String id, int exact, double sem, int daysAgo, boolean decision, int tokens) {
        return new Candidate(id, exact, sem, NOW.minus(Duration.ofDays(daysAgo)), decision, tokens);
    }

    private static List<String> ids(List<Ranked> r) {
        return r.stream().map(x -> x.candidate().chunkId()).toList();
    }

    @Test
    void exactMatchBeatsVagueSemanticMatch() {
        // The query is an error code: the exact hit should win over a loosely similar chunk.
        List<Ranked> r = ranker(Weights.defaults()).rank(List.of(
                c("vague", 0, 0.62, 1, false, 100),
                c("exact", 3, 0.40, 1, false, 100)), NOW, 2);
        assertEquals(List.of("exact", "vague"), ids(r));
    }

    @Test
    void decisionsGetABoost() {
        List<Ranked> r = ranker(Weights.defaults()).rank(List.of(
                c("chat", 1, 0.7, 2, false, 100),
                c("decision", 1, 0.7, 2, true, 100)), NOW, 2);
        assertEquals("decision", r.get(0).candidate().chunkId());
    }

    @Test
    void recencyHalvesEveryHalfLife() {
        HybridRanker onlyRecency = ranker(new Weights(0, 0, 1, 0));
        List<Ranked> r = onlyRecency.rank(List.of(c("old", 0, 0, 14, false, 1), c("new", 0, 0, 0, false, 1)), NOW, 2);
        assertEquals(List.of("new", "old"), ids(r));
        assertEquals(1.0, r.get(0).score(), 1e-9);
        assertEquals(0.5, r.get(1).score(), 1e-9);
    }

    @Test
    void contributionsSumToTheScore() {
        Ranked r = ranker(Weights.defaults()).rank(List.of(c("x", 2, 0.8, 3, true, 10)), NOW, 1).get(0);
        double sum = r.contributions().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(r.score(), sum, 1e-12);
        assertEquals(Weights.defaults().exact(), r.contributions().get("exact"), 1e-12);  // best exact = 1.0
    }

    @Test
    void returnsAtMostKAndHandlesEmptyInput() {  // boundary
        HybridRanker h = ranker(Weights.defaults());
        assertTrue(h.rank(List.of(), NOW, 5).isEmpty());
        List<Ranked> r = h.rank(List.of(c("a", 1, 0.1, 1, false, 1), c("b", 2, 0.1, 1, false, 1),
                c("c", 3, 0.1, 1, false, 1)), NOW, 2);
        assertEquals(List.of("c", "b"), ids(r));
    }

    @Test
    void budgetSkipsChunksThatDoNotFit() {
        HybridRanker h = ranker(new Weights(1, 0, 0, 0));
        List<Ranked> ranked = h.rank(List.of(c("big", 5, 0, 0, false, 900), c("mid", 4, 0, 0, false, 300),
                c("small", 3, 0, 0, false, 150)), NOW, 3);
        assertEquals(List.of("mid", "small"), ids(HybridRanker.fitToBudget(ranked, 500)));
        assertTrue(HybridRanker.fitToBudget(ranked, 0).isEmpty());
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new Weights(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Weights(-1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Weights(Double.NaN, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new HybridRanker(Weights.defaults(), Duration.ZERO, new RandomizedSelect(1)));
        assertThrows(IllegalArgumentException.class, () -> ranker(Weights.defaults()).rank(List.of(), NOW, 0));
        assertThrows(IllegalArgumentException.class, () -> c("x", -1, 0, 0, false, 1));
    }

    @Test
    void futureTimestampsDoNotScoreAboveOne() {  // clock skew
        Ranked r = ranker(new Weights(0, 0, 1, 0)).rank(List.of(c("future", 0, 0, -3, false, 1)), NOW, 1).get(0);
        assertEquals(1.0, r.score(), 1e-9);
    }
}
