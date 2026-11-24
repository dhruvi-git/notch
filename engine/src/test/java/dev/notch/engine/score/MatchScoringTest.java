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

    @Test
    void closestFixPicksTheSmallestRaise() {
        // Match 65 against a minimum of 70. SQL alone needs 90 (+10), Python 75 (+15), Tableau 60 (+30).
        MatchScoring.Fix fix = MatchScoring.closestFix(DATA_ANALYST, Map.of(
                "sql", new CandidateSkill(80, Level.VERIFIED),
                "python", new CandidateSkill(60, Level.SHOWN),
                "tableau", new CandidateSkill(30, Level.SHOWN)), 70).orElseThrow();
        assertEquals(new MatchScoring.Fix("sql", 80, 90, Level.VERIFIED, false), fix);
        assertEquals(10, fix.raise(), EPS);
    }

    @Test
    void closestFixCanBeJustReachingTheEvidenceLevel() {
        // SQL at 80 but only SHOWN counts half: match 40. Verified, it counts in full: (240 + 120) / 6 = 60.
        MatchScoring.Fix fix = MatchScoring.closestFix(DATA_ANALYST, Map.of(
                "sql", new CandidateSkill(80, Level.SHOWN),
                "python", new CandidateSkill(60, Level.SHOWN)), 60).orElseThrow();
        assertEquals(new MatchScoring.Fix("sql", 80, 80, Level.VERIFIED, true), fix);
        assertEquals(0, fix.raise(), EPS);
    }

    @Test
    void closestFixForAMissingMustHaveIsThatSkill() {
        // No SQL: ineligible. Python and Tableau give 45; SQL at 30, verified, brings it to 60.
        MatchScoring.Fix fix = MatchScoring.closestFix(DATA_ANALYST, Map.of(
                "python", new CandidateSkill(90, Level.SHOWN),
                "tableau", new CandidateSkill(90, Level.SHOWN)), 60).orElseThrow();
        assertEquals(new MatchScoring.Fix("sql", 0, 30, Level.VERIFIED, true), fix);
    }

    @Test
    void closestFixIsEmptyWhenNoSingleChangeIsEnough() {
        List<Requirement> twoMustHaves = List.of(new Requirement("sql", 1, true, Level.SHOWN),
                new Requirement("java", 1, true, Level.SHOWN));
        assertTrue(MatchScoring.closestFix(twoMustHaves, Map.of(), 10).isEmpty(), "two must-haves missing");
        assertTrue(MatchScoring.closestFix(DATA_ANALYST, Map.of("sql", new CandidateSkill(10, Level.VERIFIED)), 95)
                .isEmpty(), "no skill can carry it alone");
        assertTrue(MatchScoring.closestFix(DATA_ANALYST, Map.of("sql", new CandidateSkill(90, Level.VERIFIED)), 40)
                .isEmpty(), "already qualifies");
    }

    @Test
    void closestFixAlwaysReachesTheThresholdAndNothingSmallerDoes() {
        java.util.Random rnd = new java.util.Random(11);
        Level[] levels = Level.values();
        String[] names = {"sql", "python", "tableau"};
        for (int t = 0; t < 2000; t++) {
            Map<String, CandidateSkill> skills = new java.util.HashMap<>();
            for (String n : names) {
                if (rnd.nextInt(4) > 0) skills.put(n, new CandidateSkill(rnd.nextInt(101), levels[rnd.nextInt(levels.length)]));
            }
            double threshold = rnd.nextInt(101);
            var fix = MatchScoring.closestFix(DATA_ANALYST, skills, threshold);
            if (fix.isEmpty()) {
                // No single skill, raised to 100 at its level, may reach the threshold.
                for (String n : names) {
                    Map<String, CandidateSkill> raised = new java.util.HashMap<>(skills);
                    raised.put(n, new CandidateSkill(100, Level.DEFENDED));
                    Match m = MatchScoring.match(DATA_ANALYST, raised);
                    Match now = MatchScoring.match(DATA_ANALYST, skills);
                    boolean already = now.eligible() && now.score() >= threshold;
                    assertTrue(already || !(m.eligible() && m.score() >= threshold - 1e-6),
                            "missed a fix on " + n + " for " + skills + " at " + threshold);
                }
                continue;
            }
            Map<String, CandidateSkill> applied = new java.util.HashMap<>(skills);
            applied.put(fix.get().skill(), new CandidateSkill(fix.get().to(), fix.get().minLevel()));
            Match m = MatchScoring.match(DATA_ANALYST, applied);
            assertTrue(m.eligible() && m.score() >= threshold - 1e-6, "fix " + fix.get() + " for " + skills + " at " + threshold);
        }
    }
}
