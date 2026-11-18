package dev.notch.engine.score;

import dev.notch.engine.score.MatchScoring.CandidateSkill;
import dev.notch.engine.score.MatchScoring.Match;
import dev.notch.engine.score.MatchScoring.Requirement;
import dev.notch.engine.score.SkillScoring.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatchScoringTest {

    private static final double EPS = 1e-9;

    private static final List<Requirement> DATA_ANALYST = List.of(
            new Requirement("sql", 3, true, Level.VERIFIED),
            new Requirement("python", 2, false, Level.SHOWN),
            new Requirement("tableau", 1, false, Level.SHOWN));

    @Test
    void weightedAverageWithBreakdown() {
        Match m = MatchScoring.match(DATA_ANALYST, Map.of(
                "sql", new CandidateSkill(80, Level.VERIFIED),
                "python", new CandidateSkill(60, Level.SHOWN),
                "tableau", new CandidateSkill(30, Level.SHOWN)));
        // (3*80 + 2*60 + 1*30) / 6 = 65
        assertEquals(65, m.score(), EPS);
        assertTrue(m.eligible());
        assertEquals(m.score(), m.terms().stream().mapToDouble(MatchScoring.Term::contribution).sum(), EPS);
    }

    @Test
    void belowMinimumLevelCountsHalf() {
        Match m = MatchScoring.match(DATA_ANALYST, Map.of(
                "sql", new CandidateSkill(80, Level.SHOWN), // needs VERIFIED
                "python", new CandidateSkill(60, Level.SHOWN)));
        // (3*80*0.5 + 2*60) / 6 = 40
        assertEquals(40, m.score(), EPS);
        assertEquals(0.5, m.terms().get(0).levelFactor(), EPS);
    }

    @Test
    void edgeCaseMissingMustHaveMakesIneligible() {
        Match m = MatchScoring.match(DATA_ANALYST, Map.of("python", new CandidateSkill(100, Level.VERIFIED)));
        assertFalse(m.eligible());
        assertEquals(0, m.score(), EPS);
        assertEquals(List.of("sql"), m.missingMustHaves());
    }

    @Test
    void edgeCasePerfectCandidateScores100() {
        Match m = MatchScoring.match(DATA_ANALYST, Map.of(
                "sql", new CandidateSkill(100, Level.VERIFIED),
                "python", new CandidateSkill(100, Level.VERIFIED),
                "tableau", new CandidateSkill(100, Level.VERIFIED)));
        assertEquals(100, m.score(), EPS);
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> MatchScoring.match(List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Requirement("sql", 0, false, Level.SHOWN));
        assertThrows(IllegalArgumentException.class, () -> new CandidateSkill(120, Level.SHOWN));
        assertThrows(IllegalArgumentException.class, () -> MatchScoring.match(List.of(
                new Requirement("sql", 1, false, Level.SHOWN), new Requirement("sql", 2, false, Level.SHOWN)), Map.of()));
    }
}
