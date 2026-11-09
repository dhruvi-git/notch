package dev.notch.api.decisions.core;

import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.search.core.ProjectIndex;
import dev.notch.engine.select.RandomizedSelect;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DriftDetectorTest {

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    private final DriftDetector detector = new DriftDetector();
    private final UUID convA = UUID.randomUUID();
    private final UUID decisionMsg = UUID.randomUUID();

    private DecisionItem postgresDecision() {
        return new DecisionItem(UUID.randomUUID(), "Let's go with Postgres instead of MongoDB because we need joins.",
                null, List.of("MongoDB"), T0, decisionMsg, convA);
    }

    private static IndexedChunk chunk(UUID conv, String title, String text, Instant at) {
        return new IndexedChunk(UUID.randomUUID(), UUID.randomUUID(), conv, title, "assistant", at, text, 20, false);
    }

    private ProjectIndex index(IndexedChunk... chunks) {
        List<IndexedChunk> all = new ArrayList<>(List.of(chunks));
        all.add(new IndexedChunk(UUID.randomUUID(), decisionMsg, convA, "Billing DB", "user", T0,
                "Let's go with Postgres instead of MongoDB because we need joins. See src/db/pool.ts", 20, true));
        return ProjectIndex.build(all, T0.plus(Duration.ofDays(10)), new RandomizedSelect(1));
    }

    @Test
    void flagsLaterChatThatStartsUsingTheRejectedTool() {
        IndexedChunk drift = chunk(UUID.randomUUID(), "Analytics service",
                "First, run npm install mongoose and connect to MongoDB Atlas.", T0.plus(Duration.ofDays(3)));
        List<DriftAlert> alerts = detector.detect(List.of(postgresDecision()), index(drift));
        assertEquals(1, alerts.size());
        DriftAlert a = alerts.get(0);
        assertEquals(DriftAlert.Type.USAGE_DRIFT, a.type());
        assertEquals(DriftAlert.Severity.MEDIUM, a.severity());
        assertEquals(drift.chunkId(), a.evidence().chunkId());
        assertTrue(a.message().contains("starts using MongoDB") && a.message().contains("chose PostgreSQL"));
    }

    @Test
    void driftInTheSameThreadIsHigh() {
        IndexedChunk drift = chunk(UUID.randomUUID(), "Pool rewrite",
                "In src/db/pool.ts we can switch to MongoDB for the event log.", T0.plus(Duration.ofDays(2)));
        DriftAlert a = detector.detect(List.of(postgresDecision()), index(drift)).get(0);
        assertEquals(DriftAlert.Severity.HIGH, a.severity());
    }

    @Test
    void dismissiveMentionsAndEarlierChatsAreIgnored() {
        IndexedChunk dismissive = chunk(UUID.randomUUID(), "Recap",
                "We use Postgres instead of MongoDB, as agreed.", T0.plus(Duration.ofDays(4)));
        IndexedChunk earlier = chunk(UUID.randomUUID(), "Exploring",
                "Let me install mongoose and try MongoDB.", T0.minus(Duration.ofDays(1)));
        IndexedChunk passing = chunk(UUID.randomUUID(), "Chat", "MongoDB came up in the meetup.", T0.plus(Duration.ofDays(1)));
        assertTrue(detector.detect(List.of(postgresDecision()), index(dismissive, earlier, passing)).isEmpty());
    }

    @Test
    void newerDecisionChoosingTheRejectedToolConflicts() {
        DecisionItem older = postgresDecision();
        DecisionItem newer = new DecisionItem(UUID.randomUUID(), "We'll use MongoDB for the analytics events.",
                null, List.of(), T0.plus(Duration.ofDays(5)), UUID.randomUUID(), UUID.randomUUID());
        List<DriftAlert> alerts = detector.detect(List.of(newer, older), index());
        assertEquals(1, alerts.size());
        DriftAlert a = alerts.get(0);
        assertEquals(DriftAlert.Type.DECISION_CONFLICT, a.type());
        assertEquals(older.id(), a.decisionId());
        assertEquals(newer.id(), a.conflictingDecisionId());
        assertEquals(DriftAlert.Severity.HIGH, a.severity());
    }

    @Test
    void aDecisionSentenceIsNotAlsoReportedAsUsageDrift() {
        UUID newMsg = UUID.randomUUID();
        UUID conv = UUID.randomUUID();
        DecisionItem older = new DecisionItem(UUID.randomUUID(), "We chose server-side sessions instead of JWT.",
                null, List.of("JWT"), T0, decisionMsg, convA);
        DecisionItem newer = new DecisionItem(UUID.randomUUID(), "We decided to use JWT for the API tokens.",
                null, List.of(), T0.plus(Duration.ofDays(2)), newMsg, conv);
        IndexedChunk c = new IndexedChunk(UUID.randomUUID(), newMsg, conv, "API", "user", T0.plus(Duration.ofDays(2)),
                "We decided to use JWT for the API tokens.", 10, true);
        List<DriftAlert> alerts = detector.detect(List.of(older, newer), index(c));
        assertEquals(1, alerts.size());
        assertEquals(DriftAlert.Type.DECISION_CONFLICT, alerts.get(0).type());
    }

    @Test
    void sameCategoryDifferentChoiceIsMedium() {
        DecisionItem a = new DecisionItem(UUID.randomUUID(), "We chose server-side sessions.", null, List.of(), T0, null, null);
        DecisionItem b = new DecisionItem(UUID.randomUUID(), "Let's use OAuth for login.", null, List.of(),
                T0.plus(Duration.ofDays(1)), null, null);
        DriftAlert alert = detector.detect(List.of(a, b), index()).get(0);
        assertEquals(DriftAlert.Severity.MEDIUM, alert.severity());
        assertTrue(alert.message().contains("auth"));
    }

    @Test
    void noDecisionsOrUnrelatedDecisionsGiveNoAlerts() {  // boundary
        assertTrue(detector.detect(List.of(), index()).isEmpty());
        DecisionItem cache = new DecisionItem(UUID.randomUUID(), "We'll use Redis for caching.", null, List.of(),
                T0.plus(Duration.ofDays(1)), null, null);
        assertTrue(detector.detect(List.of(postgresDecision(), cache), index()).isEmpty());
    }

    @Test
    void alertKeysAreStable() {
        assertEquals(DriftDetector.key("a", "b"), DriftDetector.key("a", "b"));
        assertNotEquals(DriftDetector.key("a", "b"), DriftDetector.key("a", "c"));
        assertEquals(24, DriftDetector.key("x").length());
    }
}
