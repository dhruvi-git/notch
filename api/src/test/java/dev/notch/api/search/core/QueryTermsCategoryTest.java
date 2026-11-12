package dev.notch.api.search.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Fixes found by the search evaluation (docs/evaluation.md). */
class QueryTermsCategoryTest {

    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

    @Test
    void expansionsMatchStemmedWords() {
        // "authentication" stems to "authentic", which never matched the table's key before.
        assertTrue(QueryTerms.parse("what did we decide about authentication", NOW).expansions().contains("jwt"));
        assertTrue(QueryTerms.parse("what about hosting", NOW).expansions().contains("vercel"));
    }

    @Test
    void wordsForAKindOfToolExpandToTheTools() {
        List<String> styling = QueryTerms.parse("what did we decide about styling", NOW).expansions();
        assertTrue(styling.contains("tailwind") && styling.contains("css modules"), styling.toString());
        List<String> testing = QueryTerms.parse("which tests framework", NOW).expansions();
        assertTrue(testing.contains("vitest") && testing.contains("jest"), testing.toString());
    }

    @Test
    void shortAliasesAreNotExpansions() {  // boundary: "pg" would match inside "upgrading"
        assertFalse(QueryTerms.parse("database", NOW).expansions().contains("pg"));
    }

    @Test
    void aTimeOnlyQuestionReturnsTheWindowsPassages() {
        IndexedChunk old = chunk("Chose Postgres.", NOW.minusSeconds(30 * 86_400L));
        IndexedChunk recent = chunk("Today: added CSRF tokens to forms.", NOW.minusSeconds(86_400));
        ProjectIndex idx = ProjectIndex.build(List.of(old, recent), NOW, new dev.notch.engine.select.RandomizedSelect(1));
        QueryTerms q = QueryTerms.parse("what did we work on yesterday?", NOW);
        SearchPipeline.Result r = new SearchPipeline(new dev.notch.engine.select.RandomizedSelect(1))
                .rank(idx, q, java.util.Map.of(), q.since(), NOW, 5, SearchPipeline.Mode.HYBRID);
        assertEquals(1, r.ranked().size());
        assertEquals(recent.chunkId().toString(), r.ranked().get(0).candidate().chunkId());
    }

    private static IndexedChunk chunk(String text, Instant at) {
        return new IndexedChunk(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Notes", "user", at, text, 10, false);
    }
}
