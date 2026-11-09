package dev.notch.api.search.core;

import dev.notch.engine.select.RandomizedSelect;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProjectIndexTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final UUID CONV_A = UUID.randomUUID(), CONV_B = UUID.randomUUID(), CONV_C = UUID.randomUUID();

    private static IndexedChunk chunk(UUID conv, String title, String text, int daysAgo, boolean decision) {
        return new IndexedChunk(UUID.randomUUID(), UUID.randomUUID(), conv, title, "user",
                NOW.minus(Duration.ofDays(daysAgo)), text, text.length() / 4, decision);
    }

    private final IndexedChunk dbDecision = chunk(CONV_A, "Billing DB",
            "Let's go with Postgres instead of MongoDB because we need joins.", 3, true);
    private final IndexedChunk dbError = chunk(CONV_A, "Billing DB",
            "Error: connect ECONNREFUSED 127.0.0.1:5432 in src/db/pool.ts", 3, false);
    private final IndexedChunk poolFix = chunk(CONV_B, "Pool tuning",
            "Raised max connections in src/db/pool.ts to 20.", 1, false);
    private final IndexedChunk cssChunk = chunk(CONV_C, "Landing page",
            "The grid layout breaks on mobile; use minmax for columns.", 40, false);

    private ProjectIndex index() {
        return ProjectIndex.build(List.of(dbDecision, dbError, poolFix, cssChunk), NOW, new RandomizedSelect(1));
    }

    @Test
    void exactScoresFavourRareTerms() {
        Map<UUID, Integer> s = index().exactScores(QueryTerms.parse("ECONNREFUSED postgres", NOW));
        assertTrue(s.containsKey(dbError.chunkId()));
        assertTrue(s.containsKey(dbDecision.chunkId()));
        assertFalse(s.containsKey(cssChunk.chunkId()));
    }

    @Test
    void stemsMatchInflectedForms() {
        Map<UUID, Integer> s = index().exactScores(QueryTerms.parse("connections", NOW));  // stem "connection"
        assertEquals(Set_of(poolFix.chunkId()), s.keySet());
    }

    private static java.util.Set<UUID> Set_of(UUID... ids) {
        return java.util.Set.of(ids);
    }

    @Test
    void conversationsSharingAFileAreOneThread() {
        ProjectIndex idx = index();
        assertEquals(idx.threadOf(CONV_A), idx.threadOf(CONV_B));
        assertNotEquals(idx.threadOf(CONV_A), idx.threadOf(CONV_C));
        assertEquals(1, idx.linkedThreads().size());
    }

    @Test
    void autocompleteSuggestsCodeKeysAndTitles() {
        ProjectIndex idx = index();
        assertEquals("src/db/pool.ts", idx.autocomplete("src/d", 5).get(0).key());
        assertEquals("ECONNREFUSED", idx.autocomplete("econn", 5).get(0).key());
        assertTrue(idx.autocomplete("Billing", 5).stream().anyMatch(s -> s.key().equals("Billing DB")));
    }

    @Test
    void highlightsAreMergedSpans() {
        String text = "postgres and Postgres-backed postgresql";
        List<int[]> spans = ProjectIndex.highlights(text, QueryTerms.parse("postgres", NOW));
        assertEquals(3, spans.size());
        assertEquals("Postgre", text.substring(spans.get(1)[0], spans.get(1)[1]));
    }

    @Test
    void decisionChunksRespectTheTimeWindow() {
        ProjectIndex idx = index();
        assertEquals(1, idx.decisionChunks(null).size());
        assertTrue(idx.decisionChunks(NOW.minus(Duration.ofDays(1))).isEmpty());
    }

    @Test
    void emptyIndexIsSafe() {  // boundary
        ProjectIndex idx = ProjectIndex.build(List.of(), NOW, new RandomizedSelect(1));
        assertTrue(idx.isEmpty());
        assertTrue(idx.exactScores(QueryTerms.parse("anything", NOW)).isEmpty());
        assertTrue(idx.autocomplete("a", 5).isEmpty());
        assertEquals(0, idx.stats().chunks());
    }

    @Test
    void statsDescribeTheIndex() {
        ProjectIndex.Stats st = index().stats();
        assertEquals(4, st.chunks());
        assertEquals(3, st.conversations());
        assertEquals(2, st.threads());
    }
}
