package dev.notch.api.ideation.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComparisonAnalyzerTest {

    private final ComparisonAnalyzer analyzer = new ComparisonAnalyzer();

    private static Comparison.Option opt(String id, String name) {
        return new Comparison.Option(id, name, null);
    }

    private static Comparison.Criterion crit(String id, String name, int w) {
        return new Comparison.Criterion(id, name, w);
    }

    /** scores given as criterion → [score for each option, in order]. */
    private static Comparison matrix(List<Comparison.Option> options, List<Comparison.Criterion> criteria, int[][] rows) {
        Map<String, Map<String, Integer>> s = new HashMap<>();
        for (int k = 0; k < criteria.size(); k++) {
            Map<String, Integer> row = new HashMap<>();
            for (int i = 0; i < options.size(); i++) if (rows[k][i] > 0) row.put(options.get(i).id(), rows[k][i]);
            s.put(criteria.get(k).id(), row);
        }
        return new Comparison("Which database for billing?", options, criteria, s);
    }

    private final List<Comparison.Option> dbs = List.of(opt("pg", "Postgres"), opt("mongo", "MongoDB"));
    private final List<Comparison.Criterion> crits = List.of(crit("fit", "Fits relational data", 5), crit("speed", "Speed to start", 2));

    @Test
    void weightedScoreAsShareOfTheBestPossible() {
        Analysis a = analyzer.analyze(matrix(dbs, crits, new int[][] {{5, 2}, {3, 5}}));
        // Postgres: 5*5 + 2*3 = 31 of 35; MongoDB: 5*2 + 2*5 = 20 of 35.
        assertEquals("pg", a.standings().get(0).optionId());
        assertEquals(88.6, a.standings().get(0).percent());
        assertEquals(57.1, a.standings().get(1).percent());
        assertEquals(List.of("pg"), a.winnerIds());
        assertTrue(a.ready());
    }

    @Test
    void decisiveCriteriaExplainTheLead() {
        Analysis a = analyzer.analyze(matrix(dbs, crits, new int[][] {{5, 2}, {3, 5}}));
        assertEquals(1, a.decisive().size());
        assertEquals("fit", a.decisive().get(0).criterionId());
        assertEquals(15.0, a.decisive().get(0).points());
    }

    @Test
    void aClearWinnerIsRobust() {
        Analysis a = analyzer.analyze(matrix(dbs, crits, new int[][] {{5, 2}, {3, 5}}));
        assertEquals("pg", a.robustness().get(0).optionId());
        assertTrue(a.robustness().get(0).share() > 0.99, "share " + a.robustness().get(0).share());
        assertEquals(ComparisonAnalyzer.SAMPLES, a.samples());
    }

    @Test
    void aCloseCallIsReportedAsUncertain() {
        // Equal totals at base weights; small weight changes flip the winner.
        List<Comparison.Criterion> even = List.of(crit("a", "Cost", 3), crit("b", "Features", 3));
        Analysis a = analyzer.analyze(matrix(dbs, even, new int[][] {{5, 3}, {3, 5}}));
        assertEquals(2, a.winnerIds().size(), "a tie");
        assertFalse(a.ready());
        double top = a.robustness().get(0).share();
        assertTrue(top > 0.35 && top < 0.65, "share " + top);
        assertEquals(1.0, a.robustness().stream().mapToDouble(Analysis.Share::share).sum(), 0.002);
    }

    @Test
    void sameMatrixAlwaysGivesTheSameNumbers() {
        Comparison c = matrix(dbs, List.of(crit("a", "Cost", 3), crit("b", "Features", 4)), new int[][] {{5, 3}, {3, 4}});
        assertEquals(analyzer.analyze(c).robustness(), analyzer.analyze(c).robustness());
    }

    @Test
    void unscoredCellsAreCountedAndCountAsNothing() {
        Analysis a = analyzer.analyze(matrix(dbs, crits, new int[][] {{5, 0}, {0, 0}}));
        assertEquals(3, a.unscored());
        assertFalse(a.ready());
        assertEquals("pg", a.winnerIds().get(0));
    }

    @Test
    void invalidComparisonsExplainWhatToFix() {  // invalid input
        Comparison oneOption = new Comparison("Q?", List.of(opt("a", "Only")), crits, Map.of());
        assertTrue(ComparisonAnalyzer.validate(oneOption).contains("Add at least two options to compare."));
        Comparison dupes = new Comparison("Q?", List.of(opt("a", "Redis"), opt("b", "redis ")), crits, Map.of());
        assertTrue(ComparisonAnalyzer.validate(dupes).get(0).startsWith("Two options are both called"));
        Comparison badScore = new Comparison("Q?", dbs, crits, Map.of("fit", Map.of("pg", 9)));
        assertTrue(ComparisonAnalyzer.validate(badScore).contains("Scores go from 1 to 5."));
        Comparison badWeight = new Comparison("Q?", dbs, List.of(crit("x", "Cost", 0)), Map.of());
        assertTrue(ComparisonAnalyzer.validate(badWeight).contains("Weights go from 1 to 5."));
        Analysis a = analyzer.analyze(oneOption);
        assertTrue(a.standings().isEmpty());
        assertFalse(a.problems().isEmpty());
    }

    @Test
    void emptyComparisonIsSafe() {  // boundary
        Analysis a = analyzer.analyze(new Comparison(null, null, null, null));
        assertTrue(a.standings().isEmpty());
        assertTrue(a.problems().contains("Write the question you're deciding."));
    }

    @Test
    void ranksShareTiesAndTheRestFollow() {
        List<Comparison.Option> three = List.of(opt("a", "A"), opt("b", "B"), opt("c", "C"));
        Analysis a = analyzer.analyze(matrix(three, List.of(crit("x", "X", 2)), new int[][] {{4, 4, 2}}));
        assertEquals(List.of(1, 1, 3), a.standings().stream().map(Analysis.Standing::rank).toList());
    }
}
