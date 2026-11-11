package dev.notch.api.llm;

import dev.notch.api.search.core.QueryTerms;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OfflineAnswererTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private final OfflineAnswerer answerer = new OfflineAnswerer();

    private static AnswerRequest.Context ctx(int n, String text, boolean decision) {
        return new AnswerRequest.Context(n, UUID.randomUUID(), "t", NOW, text, decision);
    }

    private AnswerDraft ask(String q, AnswerRequest.Context... contexts) {
        return answerer.answer(new AnswerRequest(q, QueryTerms.parse(q, NOW), List.of(contexts)));
    }

    @Test
    void quotesAndCitesMatchingSentences() {
        AnswerRequest.Context a = ctx(1, "We compared options. Let's go with Postgres because we need joins.", true);
        AnswerDraft d = ask("which database did we pick, postgres?", a);
        assertTrue(d.grounded());
        assertEquals("Here's what this project decided:\n- Let's go with Postgres because we need joins. [1]", d.text());
        AnswerDraft.Citation c = d.citations().get(0);
        assertEquals("Let's go with Postgres because we need joins.", a.text().substring(c.start(), c.end()));
    }

    @Test
    void citationsAreRenumberedInOrder() {
        AnswerDraft d = ask("redis limits",
                ctx(1, "Nothing relevant here at all.", false),
                ctx(2, "Redis holds the rate limits.", false),
                ctx(3, "Limits are 60 per minute in Redis.", false));
        assertEquals(List.of(1, 2), d.citations().stream().map(AnswerDraft.Citation::n).toList());
        assertTrue(d.text().endsWith("[2]"));
    }

    @Test
    void decisionQuestionWithoutTopicReturnsDecisions() {
        AnswerDraft d = ask("what did we decide last week?",
                ctx(1, "We decided to use server-side sessions.", true),
                ctx(2, "The weather was nice.", false));
        assertEquals(1, d.citations().size());
    }

    @Test
    void saysSoWhenNothingSupportsAnAnswer() {  // boundary
        AnswerDraft d = ask("kubernetes autoscaling", ctx(1, "We use Redis for caching.", false));
        assertFalse(d.grounded());
        assertTrue(d.citations().isEmpty());
        assertEquals(OfflineAnswerer.NOTHING_FOUND, d.text());
        assertFalse(ask("anything", new AnswerRequest.Context[0]).grounded());
    }

    @Test
    void decisionQuestionsAreAnsweredWithDecisionsNotMentions() {
        AnswerDraft d = ask("what did we decide about the database?",
                ctx(1, "Here's my env: DATABASE_URL=postgres://localhost/billing", false),
                ctx(2, "Let's go with Postgres instead of MongoDB because we need joins.", true));
        assertEquals(1, d.citations().size());
        assertTrue(d.text().contains("Let's go with Postgres"));
    }

    @Test
    void questionsAndChecklistMarkersAreNotEchoed() {
        AnswerDraft d = ask("rate limit",
                ctx(1, "How should we rate limit per user?\n- [ ] add rate limit headers to responses", false));
        assertEquals("Here's what I found in this project:\n- add rate limit headers to responses [1]", d.text());
    }

    @Test
    void theSameSentenceFromTwoSourcesIsSaidOnce() {
        // A decision made in a chat and copied into notes.
        AnswerDraft d = ask("what did we decide about the database?",
                ctx(1, "Let's go with Postgres instead of MongoDB because we need joins.", true),
                ctx(2, "Notes. Let's go with Postgres instead of MongoDB because we need joins.", true));
        assertEquals(1, d.citations().size(), d.text());
    }

    @Test
    void limitsSentencesPerChunkAndOverall() {
        AnswerDraft d = ask("redis",
                ctx(1, "Redis one. Redis two. Redis three. Redis four.", false),
                ctx(2, "Redis five. Redis six. Redis seven.", false),
                ctx(3, "Redis eight. Redis nine.", false));
        assertEquals(OfflineAnswerer.MAX_SENTENCES, d.citations().size());
        assertTrue(d.citations().stream().filter(c -> c.n() == 1).count() <= OfflineAnswerer.MAX_PER_CHUNK);
    }
}
