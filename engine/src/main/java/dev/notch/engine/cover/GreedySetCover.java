package dev.notch.engine.cover;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Weighted set cover by the greedy rule: repeatedly take the set with the lowest cost per
 * newly covered element, until everything coverable is covered or a size budget runs out.
 *
 * <p>Set cover is NP-hard. The greedy choice is the classic approximation: its total cost is at
 * most H(d) times the optimum, where d is the size of the largest set and
 * H(d) = 1 + 1/2 + … + 1/d ≤ ln d + 1. Notch uses it to choose which pieces of a candidate's
 * code to ask about: elements are the skills and files a check must touch, sets are code
 * regions, and cost is how much code a region makes the candidate read.
 *
 * <p>Time: O(k · m · u / 64) with bit sets, for m candidate sets over a universe of u elements
 * and k sets picked. Space: O(m · u / 64).
 */
public final class GreedySetCover {

    /**
     * @param picked    indices of the chosen sets, in the order chosen
     * @param covered   elements covered by the chosen sets
     * @param uncovered elements no set covers, or left over when the budget ran out
     * @param cost      total cost of the chosen sets
     */
    public record Result(List<Integer> picked, BitSet covered, BitSet uncovered, double cost) {
        public boolean complete() {
            return uncovered.isEmpty();
        }
    }

    private GreedySetCover() {
    }

    /**
     * @param universe number of elements, numbered {@code 0 .. universe-1}
     * @param sets     the elements each candidate set covers
     * @param costs    a positive cost per set
     * @param budget   the most sets to pick; {@code Integer.MAX_VALUE} for no limit
     */
    public static Result solve(int universe, List<BitSet> sets, double[] costs, int budget) {
        validate(universe, sets, costs, budget);
        BitSet need = new BitSet(universe);
        need.set(0, universe);
        BitSet coverable = new BitSet(universe);
        for (BitSet s : sets) coverable.or(s);

        BitSet covered = new BitSet(universe);
        List<Integer> picked = new ArrayList<>();
        boolean[] used = new boolean[sets.size()];
        double total = 0;
        BitSet gain = new BitSet(universe);
        while (picked.size() < budget) {
            int best = -1;
            double bestRatio = Double.POSITIVE_INFINITY;
            int bestGain = 0;
            for (int i = 0; i < sets.size(); i++) {
                if (used[i]) continue;
                gain.clear();
                gain.or(sets.get(i));
                gain.andNot(covered);
                int g = gain.cardinality();
                if (g == 0) continue;
                double ratio = costs[i] / g;
                // Lowest cost per new element; ties go to the larger gain, then the earlier set.
                if (ratio < bestRatio || (ratio == bestRatio && g > bestGain)) {
                    best = i;
                    bestRatio = ratio;
                    bestGain = g;
                }
            }
            if (best < 0) break; // nothing left that any set covers
            used[best] = true;
            picked.add(best);
            covered.or(sets.get(best));
            total += costs[best];
        }
        BitSet uncovered = (BitSet) need.clone();
        uncovered.andNot(covered);
        return new Result(Collections.unmodifiableList(picked), covered, uncovered, total);
    }

    /** H(d) = 1 + 1/2 + … + 1/d: greedy's worst-case ratio to the optimum for sets of size at most d. */
    public static double harmonic(int d) {
        double h = 0;
        for (int i = 1; i <= d; i++) h += 1.0 / i;
        return h;
    }

    private static void validate(int universe, List<BitSet> sets, double[] costs, int budget) {
        Objects.requireNonNull(sets, "sets");
        Objects.requireNonNull(costs, "costs");
        if (universe < 0) throw new IllegalArgumentException("universe must not be negative");
        if (budget < 0) throw new IllegalArgumentException("budget must not be negative");
        if (costs.length != sets.size()) throw new IllegalArgumentException("one cost per set is needed");
        for (int i = 0; i < sets.size(); i++) {
            BitSet s = Objects.requireNonNull(sets.get(i), "set " + i);
            if (s.length() > universe) throw new IllegalArgumentException("set " + i + " has an element outside the universe");
            if (!(costs[i] > 0) || Double.isInfinite(costs[i])) throw new IllegalArgumentException("cost of set " + i + " must be positive");
        }
    }
}
