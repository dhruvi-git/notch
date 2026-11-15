package dev.notch.api.review.core;

import dev.notch.api.review.ReviewReport;
import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.search.core.ProjectIndex;
import dev.notch.engine.select.RandomizedSelect;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IssueHistoryTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID DB_CHAT = UUID.randomUUID();
    private static final UUID FIX_CHAT = UUID.randomUUID();
    private static final UUID FOLLOW_UP = UUID.randomUUID();

    private static IndexedChunk chunk(UUID conversation, String title, String text, boolean decision, int daysAgo) {
        return new IndexedChunk(UUID.randomUUID(), UUID.randomUUID(), conversation, title, "user",
                NOW.minusSeconds(86_400L * daysAgo), text, text.length() / 4, decision);
    }

    private final ProjectIndex index = ProjectIndex.build(List.of(
            chunk(DB_CHAT, "Choosing a database", "Let's go with Postgres instead of MongoDB because we need joins.", true, 40),
            chunk(FIX_CHAT, "Pool errors", "Got ECONNREFUSED from src/db/pool.ts again; the pool was created per request.", false, 10),
            chunk(FOLLOW_UP, "Pool follow-up", "Moved createPool() so src/db/pool.ts makes one pool at startup.", false, 3)),
            NOW, new RandomizedSelect(1));

    private static ReviewReport.Issue issue(String category, String title, String file) {
        return new ReviewReport.Issue("high", category, title, file, null, null, null, "model");
    }

    @Test
    void termsAreTheSpecificNamesNeverPlainWords() {
        List<String> t = IssueHistory.terms(issue("bug", "saveEvent() throws ECONNREFUSED when `MONGO_URL` is unset", "src/events/store.ts"));
        assertEquals(List.of("MONGO_URL", "ECONNREFUSED", "saveEvent", "src/events/store.ts", "store.ts"), t);
        assertEquals(List.of("src/app/page.tsx"), IssueHistory.terms(issue("bug", "The page renders twice", "src/app/page.tsx")),
                "a generic file name like page.tsx isn't searched on its own");
        assertEquals(List.of("MongoDB", "src/events/store.ts", "store.ts"),
                IssueHistory.terms(issue("decision", "src/events/store.ts uses MongoDB, which a decision ruled out", "src/events/store.ts")));
    }

    @Test
    void issuesFindThePastSessionsAboutTheSameThingNewestFirst() {
        List<List<IssueHistory.Seen>> seen = IssueHistory.find(index, List.of(
                issue("bug", "Connection refused (ECONNREFUSED) under load", "src/db/pool.ts"),
                issue("decision", "src/events/store.ts uses MongoDB, which a decision ruled out", "src/events/store.ts"),
                issue("maintainability", "Long file with unclear names", null)));

        List<IssueHistory.Seen> pool = seen.get(0);
        assertEquals(2, pool.size());
        assertEquals("Pool errors", pool.get(0).conversationTitle(), "the error code itself matches first");
        assertEquals("ECONNREFUSED", pool.get(0).term());
        assertEquals("Pool follow-up", pool.get(1).conversationTitle(), "then the file path finds the follow-up");
        assertTrue(pool.get(0).excerpt().contains("ECONNREFUSED"));
        assertEquals(1, pool.get(0).related(), "the two pool sessions share a path, so they're one thread");

        IssueHistory.Seen decided = seen.get(1).get(0);
        assertEquals("Choosing a database", decided.conversationTitle());
        assertTrue(decided.decision());

        assertTrue(seen.get(2).isEmpty(), "plain words find nothing rather than everything");
    }

    @Test
    void aCommitIsShownByItsMessageNotItsFileList() {
        assertEquals("Changed in commit 3fba77c: Rebuild as a decision tool",
                IssueHistory.excerpt("Note: Commit 3fba77c9d2: Rebuild as a decision tool Changed: README.md, src/db/pool.ts, app/page.tsx", "src/db/pool.ts"));
    }

    @Test
    void anEmptyProjectHasNoHistory() {
        assertEquals(List.of(List.of()), IssueHistory.find(ProjectIndex.empty(), List.of(issue("bug", "ECONNREFUSED", null))));
    }
}
