package dev.notch.engine.score;

import dev.notch.engine.score.SkillScoring.Evidence;
import dev.notch.engine.score.SkillScoring.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillScoringTest {

    private static final double EPS = 1e-9;

    @Test
    void repoStrengthFollowsCommitsAndWeeks() {
        assertEquals(1.0, Evidence.repo(30, 8, false, false, 0).strength(), EPS);
        assertEquals(1.0, Evidence.repo(500, 40, false, false, 0).strength(), EPS); // capped
        assertEquals(0.25 + 0.25, Evidence.repo(15, 4, false, false, 0).strength(), EPS);
        assertEquals(0, Evidence.repo(100, 20, true, false, 0).strength(), EPS);  // fork
        assertEquals(0, Evidence.repo(100, 20, false, true, 0).strength(), EPS);  // duplicate
    }

    @Test
    void scoreIsNoisyOrWithHalfLife() {
        assertEquals(40.0, SkillScoring.score(List.of(Evidence.project(0))), EPS);
        assertEquals(20.0, SkillScoring.score(List.of(Evidence.project(12))), EPS); // one half-life
        // Two independent projects: 1 - 0.6 * 0.6 = 0.64.
        assertEquals(64.0, SkillScoring.score(List.of(Evidence.project(0), Evidence.project(0))), EPS);
    }

    @Test
    void moreEvidenceNeverLowersTheScoreAndOrderDoesNotMatter() {
        Evidence a = Evidence.repo(12, 3, false, false, 2), b = Evidence.chat(0.7, 1), c = Evidence.project(30);
        double ab = SkillScoring.score(List.of(a, b));
        assertTrue(SkillScoring.score(List.of(a, b, c)) >= ab);
        assertEquals(SkillScoring.score(List.of(a, b, c)), SkillScoring.score(List.of(c, a, b)), EPS);
        assertTrue(SkillScoring.score(List.of(a, b, c)) < 100);
    }

    @Test
    void edgeCaseNoEvidenceIsClaimedWithScoreZero() {
        assertEquals(0, SkillScoring.score(List.of()), EPS);
        assertEquals(Level.CLAIMED, SkillScoring.level(List.of()));
        assertEquals(Level.CLAIMED, SkillScoring.level(List.of(Evidence.repo(50, 10, true, false, 0))));
    }

    @Test
    void levelNeedsOwnCommitsAcrossWeeksToVerify() {
        assertEquals(Level.SHOWN, SkillScoring.level(List.of(Evidence.project(0))));
        assertEquals(Level.SHOWN, SkillScoring.level(List.of(Evidence.repo(40, 1, false, false, 0)))); // one sitting
        assertEquals(Level.SHOWN, SkillScoring.level(List.of(Evidence.repo(4, 6, false, false, 0))));
        assertEquals(Level.VERIFIED, SkillScoring.level(List.of(Evidence.chat(1, 0), Evidence.repo(5, 2, false, false, 0))));
        assertEquals(Level.SHOWN, SkillScoring.level(List.of(Evidence.repo(5, 2, false, true, 0), Evidence.chat(1, 0))));
    }

    @Test
    void rejectsInvalidEvidence() {
        assertThrows(IllegalArgumentException.class, () -> Evidence.repo(-1, 0, false, false, 0));
        assertThrows(IllegalArgumentException.class, () -> Evidence.project(-2));
        assertThrows(IllegalArgumentException.class, () -> Evidence.chat(1.5, 0));
        assertThrows(IllegalArgumentException.class, () -> Evidence.project(Double.NaN));
    }
}
