package dev.notch.api.ownership.core;

import dev.notch.api.ownership.core.AnswerGrader.Grade;
import dev.notch.api.ownership.core.AnswerGrader.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnswerGraderTest {

    private static final List<List<String>> RUBRIC = Concept.MODEL_TRAINING.rubric();
    private static final List<String> IDS = List.of("train_x", "test_x", "accuracy", "features");

    @Test
    void aSpecificReasonedAnswerPasses() {
        Grade g = AnswerGrader.grade(RUBRIC, IDS, """
                I hold out 20% with train_test_split, so test_x is data the model never saw during fit. \
                The accuracy is computed on that unseen split, which is how I know it generalises rather than \
                memorising the rows in train_x. If I scored on the training data the number would look better \
                than reality because of overfitting.""");
        assertTrue(g.score() >= AnswerGrader.PASS_SCORE, "score " + g.score());
        assertEquals(List.of("test", "overfit", "metric"), g.covered());
        assertTrue(g.feedback().startsWith("Covered:"));
    }

    @Test
    void aTextbookAnswerScoresBelowOneAboutThisCode() {
        String generic = "You split the data into a training set and a test set, then check the score on the test set "
                + "so the model does not overfit. This is standard practice in machine learning.";
        String specific = generic + " Here test_x is the 20% held out and the accuracy comes from it.";
        Grade g = AnswerGrader.grade(RUBRIC, IDS, generic), s = AnswerGrader.grade(RUBRIC, IDS, specific);
        assertTrue(s.score() > g.score(), s.score() + " vs " + g.score());
        assertTrue(g.feedback().contains("Didn't refer to anything named in the code"));
    }

    @Test
    void edgeCaseTooShortOrEmpty() {
        assertTrue(AnswerGrader.grade(RUBRIC, IDS, "It splits the data.").score() <= 10);
        Grade empty = AnswerGrader.grade(RUBRIC, IDS, null);
        assertEquals(0, empty.score());
        assertEquals(3, empty.missed().size());
    }

    @Test
    void integrityFlags() {
        String long60 = "word ".repeat(60);
        assertEquals(List.of("Pasted text once"), AnswerGrader.integrityFlags("a b c", 60_000, 1, 0));
        assertEquals(List.of("Left the page 3 times"), AnswerGrader.integrityFlags("a b c", 60_000, 0, 3));
        assertEquals(List.of("Answered at over 120 words a minute"), AnswerGrader.integrityFlags(long60, 10_000, 0, 0));
        assertEquals(List.of(), AnswerGrader.integrityFlags(long60, 60_000, 0, 1));
    }

    @Test
    void verdicts() {
        Verdict pass = AnswerGrader.verdict(List.of(80, 75, 70), 3, 1);
        assertTrue(pass.defended());
        assertEquals(75, pass.score());
        Verdict unanswered = AnswerGrader.verdict(List.of(90, 90), 3, 0);
        assertFalse(unanswered.defended());
        assertEquals(60, unanswered.score()); // a missing answer counts as 0
        assertTrue(unanswered.summary().contains("1 question left unanswered"));
        assertFalse(AnswerGrader.verdict(List.of(90, 90, 90), 3, 2).defended());
        assertFalse(AnswerGrader.verdict(List.of(60, 65, 70), 3, 0).defended());
        assertFalse(AnswerGrader.verdict(List.of(), 0, 0).defended());
    }
}
