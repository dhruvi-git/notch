package dev.notch.engine.cover;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GreedySetCoverTest {

    private static BitSet of(int... xs) {
        BitSet b = new BitSet();
        for (int x : xs) b.set(x);
        return b;
    }

    @Test
    void picksCheapestPerNewElement() {
        // Universe {0..5}. The big set costs 3 (0.5 per element) and beats three cheap pairs (1 per element).
        List<BitSet> sets = List.of(of(0, 1), of(2, 3), of(4, 5), of(0, 1, 2, 3, 4, 5));
        GreedySetCover.Result r = GreedySetCover.solve(6, sets, new double[]{2, 2, 2, 3}, Integer.MAX_VALUE);
        assertEquals(List.of(3), r.picked());
        assertTrue(r.complete());
        assertEquals(3, r.cost(), 1e-12);
    }

    @Test
    void textbookCaseShowsTheGreedyGap() {
        // Unit costs. Optimum: the two halves {0,2,4,6,8,10} and {1,3,5,7,9,11} (2 sets).
        // Greedy takes the largest set first (6 elements), then needs more: a valid cover within H(6) × 2.
        List<BitSet> sets = List.of(of(0, 2, 4, 6, 8, 10), of(1, 3, 5, 7, 9, 11),
                of(0, 1, 2, 3, 4, 5, 6), of(7, 8), of(9, 10, 11));
        GreedySetCover.Result r = GreedySetCover.solve(12, sets, new double[]{1, 1, 1, 1, 1}, Integer.MAX_VALUE);
        assertTrue(r.complete());
        assertEquals(2, r.picked().get(0)); // seven new elements first
        assertTrue(r.cost() <= GreedySetCover.harmonic(7) * 2 + 1e-9);
    }

    @Test
    void staysWithinTheHarmonicBoundOfTheOptimumOnRandomInstances() {
        Random rnd = new Random(55);
        for (int t = 0; t < 300; t++) {
            int u = 1 + rnd.nextInt(10), m = 1 + rnd.nextInt(9);
            List<BitSet> sets = new ArrayList<>();
            double[] costs = new double[m];
            int maxSize = 0;
            for (int i = 0; i < m; i++) {
                BitSet s = new BitSet();
                for (int e = 0; e < u; e++) if (rnd.nextInt(3) == 0) s.set(e);
                sets.add(s);
                costs[i] = 1 + rnd.nextInt(5);
                maxSize = Math.max(maxSize, s.cardinality());
            }
            GreedySetCover.Result g = GreedySetCover.solve(u, sets, costs, Integer.MAX_VALUE);
            // Whatever greedy leaves uncovered, no set covers either.
            BitSet coverable = new BitSet();
            sets.forEach(coverable::or);
            BitSet expectUncovered = new BitSet();
            expectUncovered.set(0, u);
            expectUncovered.andNot(coverable);
            assertEquals(expectUncovered, g.uncovered(), "instance " + t);
            double opt = exact(coverable, sets, costs);
            assertTrue(g.cost() >= opt - 1e-9, "greedy can't beat the optimum");
            assertTrue(g.cost() <= GreedySetCover.harmonic(Math.max(1, maxSize)) * opt + 1e-9,
                    "instance " + t + ": greedy " + g.cost() + " vs optimum " + opt);
        }
    }

    @Test
    void edgeCaseBudgetStopsEarlyAndReportsWhatIsLeft() {
        List<BitSet> sets = List.of(of(0), of(1), of(2));
        GreedySetCover.Result r = GreedySetCover.solve(3, sets, new double[]{1, 1, 1}, 2);
        assertEquals(2, r.picked().size());
        assertEquals(1, r.uncovered().cardinality());
        assertFalse(r.complete());
    }

    @Test
    void edgeCaseNothingToCoverOrNothingCoverable() {
        assertTrue(GreedySetCover.solve(0, List.of(), new double[0], 5).complete());
        GreedySetCover.Result r = GreedySetCover.solve(3, List.of(of()), new double[]{1}, 5);
        assertEquals(List.of(), r.picked());
        assertEquals(of(0, 1, 2), r.uncovered());
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> GreedySetCover.solve(-1, List.of(), new double[0], 1));
        assertThrows(IllegalArgumentException.class, () -> GreedySetCover.solve(2, List.of(of(5)), new double[]{1}, 1));
        assertThrows(IllegalArgumentException.class, () -> GreedySetCover.solve(2, List.of(of(1)), new double[]{0}, 1));
        assertThrows(IllegalArgumentException.class, () -> GreedySetCover.solve(2, List.of(of(1)), new double[]{1, 2}, 1));
        assertThrows(IllegalArgumentException.class, () -> GreedySetCover.solve(2, List.of(of(1)), new double[]{1}, -1));
        assertThrows(IllegalArgumentException.class, () -> GreedySetCover.solve(2, List.of(of(1)), new double[]{Double.NaN}, 1));
    }

    /** The cheapest cover of everything coverable, by trying every subset of sets. */
    static double exact(BitSet coverable, List<BitSet> sets, double[] costs) {
        double best = Double.POSITIVE_INFINITY;
        for (int mask = 0; mask < (1 << sets.size()); mask++) {
            BitSet c = new BitSet();
            double cost = 0;
            for (int i = 0; i < sets.size(); i++) {
                if ((mask >> i & 1) == 1) {
                    c.or(sets.get(i));
                    cost += costs[i];
                }
            }
            if (cost < best && c.equals(coverable)) best = cost;
        }
        return best;
    }
}
