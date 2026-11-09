package dev.notch.api.llm;

import dev.notch.api.llm.ExtractionRequest.SourceMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeuristicMemoryExtractorTest {

    private final HeuristicMemoryExtractor x = new HeuristicMemoryExtractor();

    private List<ExtractedMemory> run(SourceMessage... msgs) {
        return x.extractMemories(new ExtractionRequest("t", List.of(msgs)));
    }

    @Test
    void findsDecisionWithRationaleAndRejectedOption() {
        List<ExtractedMemory> m = run(new SourceMessage("user",
                "Let's go with Postgres instead of MongoDB because we need joins and pgvector."));
        assertEquals(1, m.size());
        ExtractedMemory d = m.get(0);
        assertEquals(MemoryKind.DECISION, d.kind());
        assertEquals("we need joins and pgvector", d.rationale());
        assertEquals(List.of("MongoDB"), d.rejectedOptions());
    }

    @Test
    void rejectedOptionCanBeSeveralWords() {
        ExtractedMemory d = run(new SourceMessage("user", "We chose an ORM rather than raw SQL queries.")).get(0);
        assertEquals(List.of("raw SQL queries"), d.rejectedOptions());
    }

    @Test
    void sourceSpanPointsAtTheSentence() {
        String text = "Some context here. We decided to cache sessions in Redis. More text after.";
        ExtractedMemory d = run(new SourceMessage("assistant", text)).get(0);
        assertEquals("We decided to cache sessions in Redis.", text.substring(d.start(), d.end()));
    }

    @Test
    void questionsAreNotDecisions() {
        assertTrue(run(new SourceMessage("user", "Should we go with Postgres or should we use SQLite?")).isEmpty());
    }

    @Test
    void findsTasksFromChecklistsAndCues() {
        List<ExtractedMemory> m = run(new SourceMessage("user", "- [ ] add rate limiting to login\nNext step is wiring the webhook."));
        assertEquals(2, m.size());
        assertTrue(m.stream().allMatch(e -> e.kind() == MemoryKind.TASK));
        assertEquals("add rate limiting to login", m.get(0).title());
    }

    @Test
    void findsErrorFixPairs() {
        List<ExtractedMemory> m = run(
                new SourceMessage("user", "Running the app:\nError: connect ECONNREFUSED 127.0.0.1:5432"),
                new SourceMessage("assistant", "The issue is Postgres isn't running. Start it with docker compose up -d."));
        ExtractedMemory fix = m.stream().filter(e -> e.kind() == MemoryKind.FIX).findFirst().orElseThrow();
        assertEquals("Fix: Error: connect ECONNREFUSED 127.0.0.1:5432", fix.title());
        assertTrue(fix.body().startsWith("The issue is Postgres"));
        assertEquals(0, fix.messageIndex());
    }

    @Test
    void ordinaryWordsDoNotLookLikeErrors() {
        List<ExtractedMemory> m = run(
                new SourceMessage("user", "Every endpoint returns quickly now."),
                new SourceMessage("assistant", "Great, the fix worked."));
        assertTrue(m.isEmpty());
    }

    @Test
    void emptyConversationGivesNothing() {  // boundary
        assertTrue(x.extractMemories(new ExtractionRequest("t", List.of())).isEmpty());
    }

    @Test
    void duplicatesAreCollapsedAndOutputIsCapped() {
        String repeated = "We decided to use Redis. ".repeat(3);
        assertEquals(1, run(new SourceMessage("user", repeated)).size());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 80; i++) many.append("We decided to use tool").append(i).append(". ");
        assertEquals(HeuristicMemoryExtractor.MAX_PER_CONVERSATION, run(new SourceMessage("user", many.toString())).size());
    }
}
