package dev.notch.engine.flow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Assigns candidates to the seats of several roles in one hiring drive, filling as many seats
 * as possible with eligible candidates, using {@link MaxFlow}.
 *
 * <p>Network: source → role (capacity = seats) → candidate (capacity 1, only when the
 * candidate's match score reaches the role's threshold) → sink (capacity 1). Capacities are
 * integers, so the maximum flow is integral: each role → candidate edge carries 0 or 1, which
 * is an assignment where nobody fills two seats and no role is over-filled. By max-flow
 * min-cut, no valid assignment fills more seats.
 *
 * <p>Each role's candidate edges are added highest score first, so among equally short
 * augmenting paths the search prefers better-matched candidates. That is a tie-break, not a
 * guarantee: the result maximizes seats filled, not total score. {@link #bestFit} adds that,
 * with min-cost flow.
 */
public final class SeatAssigner {

    /** A role in the drive. {@code threshold} is the minimum match score, 0 to 100. */
    public record Role(String id, int seats, double threshold) {
        public Role {
            Objects.requireNonNull(id, "role id");
            if (seats < 0) throw new IllegalArgumentException("seats must not be negative for role " + id);
            if (!(threshold >= 0 && threshold <= 100)) {
                throw new IllegalArgumentException("threshold must be in [0, 100] for role " + id);
            }
        }
    }

    public record Assignment(String roleId, String candidateId, double score) {
    }

    /**
     * The outcome of a run.
     *
     * @param assignments   who goes where, grouped by role in input order
     * @param unfilled      empty seats per role id, in input order (0 when full)
     * @param eligiblePairs role/candidate pairs at or above the role's threshold
     * @param augmentations augmenting paths found: seats filled from a cold start, or seats
     *                      beyond the greedy assignment from a warm start
     */
    public record Result(List<Assignment> assignments, Map<String, Integer> unfilled, int totalSeats,
                         int eligiblePairs, int augmentations) {
        public int seatsFilled() {
            return assignments.size();
        }

        public double totalScore() {
            return assignments.stream().mapToDouble(Assignment::score).sum();
        }
    }

    private SeatAssigner() {
    }

    /**
     * Runs the assignment.
     *
     * @param roles        the roles, each with its seats and threshold
     * @param candidateIds the candidates, ids unique
     * @param scores       {@code scores[c][r]} = match score of candidate c for role r, 0 to 100
     */
    public static Result assign(List<Role> roles, List<String> candidateIds, double[][] scores) {
        return assign(roles, candidateIds, scores, true);
    }

    /**
     * As {@link #assign(List, List, double[][])}, choosing whether to warm-start.
     *
     * <p>With {@code warmStart}, the network is first loaded with the {@link #greedy} assignment
     * (a feasible flow), and Edmonds–Karp only searches for the augmenting paths greedy missed.
     * The result is the same maximum; the number of breadth-first searches drops from one per
     * seat filled to one per seat greedy missed, plus one. Without it, the run is plain
     * Edmonds–Karp from zero flow, kept for benchmarks and tests.
     */
    public static Result assign(List<Role> roles, List<String> candidateIds, double[][] scores, boolean warmStart) {
        validate(roles, candidateIds, scores);
        int r = roles.size(), c = candidateIds.size();
        int source = 0, sink = 1, roleBase = 2, candBase = 2 + r;
        MaxFlow net = new MaxFlow(2 + r + c);

        int totalSeats = 0;
        int[] sourceEdge = new int[r];
        for (int j = 0; j < r; j++) {
            sourceEdge[j] = net.addEdge(source, roleBase + j, roles.get(j).seats());
            totalSeats += roles.get(j).seats();
        }

        // Role -> candidate edges, best score first within each role.
        int[][] edgeIds = new int[r][];
        int[][] edgeCands = new int[r][];
        int eligible = 0;
        for (int j = 0; j < r; j++) {
            List<Integer> ok = eligibleFor(j, roles.get(j), scores);
            int roleJ = j;
            ok.sort((a, b) -> Double.compare(scores[b][roleJ], scores[a][roleJ]));
            edgeIds[j] = new int[ok.size()];
            edgeCands[j] = new int[ok.size()];
            for (int k = 0; k < ok.size(); k++) {
                int cand = ok.get(k);
                edgeCands[j][k] = cand;
                edgeIds[j][k] = net.addEdge(roleBase + j, candBase + cand, 1);
            }
            eligible += ok.size();
        }
        int[] sinkEdge = new int[c];
        for (int i = 0; i < c; i++) sinkEdge[i] = net.addEdge(candBase + i, sink, 1);

        if (warmStart) {
            // Load the greedy assignment as a starting flow: one unit along s -> role -> candidate -> t each.
            int[][] edgeTo = new int[r][c];
            for (int j = 0; j < r; j++) for (int k = 0; k < edgeIds[j].length; k++) edgeTo[j][edgeCands[j][k]] = edgeIds[j][k];
            for (int[] p : greedyChoice(roles, scores)) {
                int cand = p[0], j = p[1];
                net.push(sourceEdge[j], 1);
                net.push(edgeTo[j][cand], 1);
                net.push(sinkEdge[cand], 1);
            }
        }

        net.maxFlow(source, sink);

        List<Assignment> out = new ArrayList<>();
        Map<String, Integer> unfilled = new LinkedHashMap<>();
        for (int j = 0; j < r; j++) {
            int filled = 0;
            for (int k = 0; k < edgeIds[j].length; k++) {
                if (net.flowOn(edgeIds[j][k]) == 1) {
                    int cand = edgeCands[j][k];
                    out.add(new Assignment(roles.get(j).id(), candidateIds.get(cand), scores[cand][j]));
                    filled++;
                }
            }
            unfilled.put(roles.get(j).id(), roles.get(j).seats() - filled);
        }
        return new Result(Collections.unmodifiableList(out), Collections.unmodifiableMap(unfilled),
                totalSeats, eligible, net.augmentations());
    }

    /**
     * Best fit: as many seats as {@link #assign} fills, and among all assignments that fill that
     * many, the one with the highest total match score.
     *
     * <p>Same network as {@link #assign}, with a cost on each role → candidate edge of minus the
     * match score (in hundredths, so costs are integers). {@link MinCostFlow} finds the cheapest
     * maximum flow, which is the highest-scoring assignment among those that fill the most seats.
     * Max-flow alone only promises the seat count: it keeps whichever pairing it reaches first.
     * {@code augmentations} is the number of shortest paths, one per seat filled.
     */
    public static Result bestFit(List<Role> roles, List<String> candidateIds, double[][] scores) {
        validate(roles, candidateIds, scores);
        int r = roles.size(), c = candidateIds.size();
        int source = 0, sink = 1, roleBase = 2, candBase = 2 + r;
        MinCostFlow net = new MinCostFlow(2 + r + c);

        int totalSeats = 0, eligible = 0;
        for (int j = 0; j < r; j++) {
            net.addEdge(source, roleBase + j, roles.get(j).seats(), 0);
            totalSeats += roles.get(j).seats();
        }
        int[][] edgeIds = new int[r][];
        int[][] edgeCands = new int[r][];
        for (int j = 0; j < r; j++) {
            List<Integer> ok = eligibleFor(j, roles.get(j), scores);
            int roleJ = j;
            ok.sort((a, b) -> Double.compare(scores[b][roleJ], scores[a][roleJ]));
            edgeIds[j] = new int[ok.size()];
            edgeCands[j] = new int[ok.size()];
            for (int k = 0; k < ok.size(); k++) {
                int cand = ok.get(k);
                edgeCands[j][k] = cand;
                edgeIds[j][k] = net.addEdge(roleBase + j, candBase + cand, 1, -Math.round(scores[cand][j] * 100));
            }
            eligible += ok.size();
        }
        for (int i = 0; i < c; i++) net.addEdge(candBase + i, sink, 1, 0);

        net.solve(source, sink);

        List<Assignment> out = new ArrayList<>();
        Map<String, Integer> unfilled = new LinkedHashMap<>();
        for (int j = 0; j < r; j++) {
            int filled = 0;
            for (int k = 0; k < edgeIds[j].length; k++) {
                if (net.flowOn(edgeIds[j][k]) == 1) {
                    int cand = edgeCands[j][k];
                    out.add(new Assignment(roles.get(j).id(), candidateIds.get(cand), scores[cand][j]));
                    filled++;
                }
            }
            unfilled.put(roles.get(j).id(), roles.get(j).seats() - filled);
        }
        return new Result(Collections.unmodifiableList(out), Collections.unmodifiableMap(unfilled),
                totalSeats, eligible, net.augmentations());
    }

    /**
     * Baseline: take every eligible role/candidate pair, highest score first, and keep a pair
     * when the candidate is still free and the role still has a seat. O(P log P) for P pairs.
     * It never revisits a choice, so it can leave seats empty that {@link #assign} fills.
     */
    public static Result greedy(List<Role> roles, List<String> candidateIds, double[][] scores) {
        validate(roles, candidateIds, scores);
        int r = roles.size();
        int[] left = new int[r];
        int totalSeats = 0, pairs = 0;
        List<List<Assignment>> byRole = new ArrayList<>();
        for (int j = 0; j < r; j++) {
            left[j] = roles.get(j).seats();
            totalSeats += left[j];
            pairs += eligibleFor(j, roles.get(j), scores).size();
            byRole.add(new ArrayList<>());
        }
        for (int[] p : greedyChoice(roles, scores)) {
            int cand = p[0], j = p[1];
            left[j]--;
            byRole.get(j).add(new Assignment(roles.get(j).id(), candidateIds.get(cand), scores[cand][j]));
        }
        List<Assignment> out = new ArrayList<>();
        Map<String, Integer> unfilled = new LinkedHashMap<>();
        for (int j = 0; j < r; j++) {
            out.addAll(byRole.get(j));
            unfilled.put(roles.get(j).id(), left[j]);
        }
        return new Result(Collections.unmodifiableList(out), Collections.unmodifiableMap(unfilled),
                totalSeats, pairs, 0);
    }

    /** The pairs greedy keeps, as {candidate, role}, in the order it takes them. */
    private static List<int[]> greedyChoice(List<Role> roles, double[][] scores) {
        int r = roles.size();
        List<int[]> pairs = new ArrayList<>();
        for (int j = 0; j < r; j++) {
            for (int cand : eligibleFor(j, roles.get(j), scores)) pairs.add(new int[]{cand, j});
        }
        pairs.sort((a, b) -> Double.compare(scores[b[0]][b[1]], scores[a[0]][a[1]]));
        int[] left = new int[r];
        for (int j = 0; j < r; j++) left[j] = roles.get(j).seats();
        boolean[] used = new boolean[scores.length];
        List<int[]> kept = new ArrayList<>();
        for (int[] p : pairs) {
            if (used[p[0]] || left[p[1]] == 0) continue;
            used[p[0]] = true;
            left[p[1]]--;
            kept.add(p);
        }
        return kept;
    }

    private static List<Integer> eligibleFor(int j, Role role, double[][] scores) {
        List<Integer> ok = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            if (scores[i][j] >= role.threshold()) ok.add(i);
        }
        return ok;
    }

    private static void validate(List<Role> roles, List<String> candidateIds, double[][] scores) {
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(candidateIds, "candidateIds");
        Objects.requireNonNull(scores, "scores");
        if (roles.isEmpty()) throw new IllegalArgumentException("a drive needs at least one role");
        Set<String> seen = new HashSet<>();
        for (Role role : roles) {
            Objects.requireNonNull(role, "role");
            if (!seen.add(role.id())) throw new IllegalArgumentException("duplicate role id: " + role.id());
        }
        seen.clear();
        for (String id : candidateIds) {
            Objects.requireNonNull(id, "candidate id");
            if (!seen.add(id)) throw new IllegalArgumentException("duplicate candidate id: " + id);
        }
        if (scores.length != candidateIds.size()) {
            throw new IllegalArgumentException("scores has " + scores.length + " rows but there are "
                    + candidateIds.size() + " candidates");
        }
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] == null || scores[i].length != roles.size()) {
                throw new IllegalArgumentException("scores row " + i + " must have one score per role ("
                        + roles.size() + ")");
            }
            for (double s : scores[i]) {
                if (!(s >= 0 && s <= 100)) {
                    throw new IllegalArgumentException("score out of [0, 100] for candidate "
                            + candidateIds.get(i) + ": " + Arrays.toString(scores[i]));
                }
            }
        }
    }
}
