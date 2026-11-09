package dev.notch.api.search.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryTermsTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @Test
    void dropsStopwordsAndStemsOrdinaryWords() {
        QueryTerms q = QueryTerms.parse("how are we caching sessions?", NOW);
        assertEquals(List.of("cach", "session"), q.terms());
        assertFalse(q.decisionIntent());
    }

    @Test
    void keepsIdentifiersAndErrorCodesWhole() {
        QueryTerms q = QueryTerms.parse("ECONNREFUSED in src/db/pool.ts useEffect", NOW);
        assertEquals(List.of("econnrefused", "src/db/pool.ts", "useeffect"), q.terms());
    }

    @Test
    void detectsDecisionIntentAndTimeWindow() {
        QueryTerms q = QueryTerms.parse("What did we decide last week?", NOW);
        assertTrue(q.decisionIntent());
        assertTrue(q.isEmpty());
        assertEquals(NOW.minus(Duration.ofDays(14)), q.since());
        assertEquals(NOW.minus(Duration.ofDays(30)), QueryTerms.parse("auth in the last 30 days", NOW).since());
    }

    @Test
    void quotedPhrasesAreKeptExactly() {
        QueryTerms q = QueryTerms.parse("\"connection refused\" postgres", NOW);
        assertEquals(List.of("connection refused"), q.phrases());
        assertEquals(List.of("connection refused", "postgre"), q.patterns());
    }

    @Test
    void blankOrStopwordOnlyQueryIsEmpty() {  // boundary
        assertTrue(QueryTerms.parse("   ", NOW).isEmpty());
        assertTrue(QueryTerms.parse("what is it", NOW).isEmpty());
        assertNull(QueryTerms.parse("redis", NOW).since());
    }

    @Test
    void broadWordsAreExpandedToToolNames() {
        QueryTerms q = QueryTerms.parse("which database?", NOW);
        assertEquals(List.of("database"), q.terms());
        assertTrue(q.expansions().contains("postgre"));
        assertTrue(QueryTerms.parse("ECONNREFUSED", NOW).expansions().isEmpty());
    }

    @Test
    void stemmingNeverCutsBelowFourCharacters() {
        assertEquals("redis", QueryTerms.stem("redis"));
        assertEquals("migr", QueryTerms.stem("migrations"));
        assertEquals("limit", QueryTerms.stem("limiting"));
        assertEquals("strateg", QueryTerms.stem("strategies"));
        assertEquals("cach", QueryTerms.stem("caches"));
        assertEquals("postgre", QueryTerms.stem("postgres"));
    }
}
