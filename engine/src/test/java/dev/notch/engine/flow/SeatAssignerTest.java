package dev.notch.engine.flow;

import dev.notch.engine.flow.SeatAssigner.Assignment;
import dev.notch.engine.flow.SeatAssigner.Result;
import dev.notch.engine.flow.SeatAssigner.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SeatAssignerTest {

    @Test
    void fillsTheSeatGreedyLeavesEmpty() {
        // A and B have one seat each. X fits both and scores best at A; Y fits only A.
        List<Role> roles = List.of(new Role("A", 1, 60), new Role("B", 1, 60));
        List<String> cands = List.of("X", "Y");
        double[][] scores = {{90, 80}, {70, 10}};

        Result greedy = SeatAssigner.greedy(roles, cands, scores);
        assertEquals(1, greedy.seatsFilled());
        assertEquals(1, greedy.unfilled().get("B"));

        Result flow = SeatAssigner.assign(roles, cands, scores);
        assertEquals(2, flow.seatsFilled());
        assertEquals(1, flow.augmentations()); // one path past greedy: s-B-X-A-Y-t, re-routing X
        assertTrue(flow.assignments().contains(new Assignment("A", "Y", 70)));
        assertTrue(flow.assignments().contains(new Assignment("B", "X", 80)));
        assertEquals(Map.of("A", 0, "B", 0), flow.unfilled());
    }

    @Test
    void matchesBruteForceOnRandomDrives() {
        Random rnd = new Random(2026);
        for (int t = 0; t < 300; t++) {
            int r = 1 + rnd.nextInt(3), c = rnd.nextInt(9);
            List<Role> roles = new ArrayList<>();
            for (int j = 0; j < r; j++) roles.add(new Role("r" + j, rnd.nextInt(4), 20 + rnd.nextInt(60)));
            List<String> cands = new ArrayList<>();
            double[][] scores = new double[c][r];
            for (int i = 0; i < c; i++) {
                cands.add("c" + i);
                for (int j = 0; j < r; j++) scores[i][j] = rnd.nextInt(101);
            }
            int optimum = bruteForce(roles, scores);
            Result cold = SeatAssigner.assign(roles, cands, scores, false);
            assertValid(roles, cands, scores, cold);
            assertEquals(optimum, cold.seatsFilled(), "cold start, drive " + t);
            assertEquals(cold.seatsFilled(), cold.augmentations());

            Result greedy = SeatAssigner.greedy(roles, cands, scores);
            assertValid(roles, cands, scores, greedy);
            assertTrue(optimum >= greedy.seatsFilled());

            Result warm = SeatAssigner.assign(roles, cands, scores, true);
            assertValid(roles, cands, scores, warm);
            assertEquals(optimum, warm.seatsFilled(), "warm start, drive " + t);
            assertEquals(optimum - greedy.seatsFilled(), warm.augmentations());
        }
    }

    @Test
    void edgeCaseNoEligibleCandidates() {
        List<Role> roles = List.of(new Role("data", 2, 90), new Role("backend", 1, 90));
        Result res = SeatAssigner.assign(roles, List.of("a", "b"), new double[][]{{50, 40}, {89.9, 10}});
        assertEquals(0, res.seatsFilled());
        assertEquals(0, res.eligiblePairs());
        assertEquals(Map.of("data", 2, "backend", 1), res.unfilled());
    }

    @Test
    void edgeCaseMoreSeatsThanCandidatesAndThresholdIsInclusive() {
        List<Role> roles = List.of(new Role("data", 5, 70), new Role("empty", 0, 0));
        Result res = SeatAssigner.assign(roles, List.of("a", "b", "c"), new double[][]{{70, 99}, {71, 99}, {100, 99}});
        assertEquals(3, res.seatsFilled());
        assertEquals(2, res.unfilled().get("data"));
        assertEquals(0, res.unfilled().get("empty"));
        assertEquals(5, res.totalSeats());
    }

    @Test
    void edgeCaseNoCandidates() {
        Result res = SeatAssigner.assign(List.of(new Role("data", 2, 50)), List.of(), new double[0][]);
        assertEquals(0, res.seatsFilled());
        assertEquals(2, res.unfilled().get("data"));
    }

    @Test
    void prefersHigherScoresWhenSeatCountIsUnaffected() {
        List<Role> roles = List.of(new Role("A", 1, 0));
        Result res = SeatAssigner.assign(roles, List.of("low", "high", "mid"), new double[][]{{40}, {95}, {60}});
        assertEquals(List.of(new Assignment("A", "high", 95)), res.assignments());
    }

    @Test
    void rejectsInvalidInput() {
        List<Role> one = List.of(new Role("A", 1, 50));
        assertThrows(IllegalArgumentException.class, () -> new Role("A", -1, 50));
        assertThrows(IllegalArgumentException.class, () -> new Role("A", 1, 101));
        assertThrows(IllegalArgumentException.class, () -> new Role("A", 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> SeatAssigner.assign(List.of(), List.of(), new double[0][]));
        assertThrows(IllegalArgumentException.class,
                () -> SeatAssigner.assign(List.of(new Role("A", 1, 50), new Role("A", 1, 50)), List.of(), new double[0][]));
        assertThrows(IllegalArgumentException.class,
                () -> SeatAssigner.assign(one, List.of("x", "x"), new double[][]{{1}, {1}}));
        assertThrows(IllegalArgumentException.class, () -> SeatAssigner.assign(one, List.of("x"), new double[0][]));
        assertThrows(IllegalArgumentException.class, () -> SeatAssigner.assign(one, List.of("x"), new double[][]{{1, 2}}));
        assertThrows(IllegalArgumentException.class, () -> SeatAssigner.assign(one, List.of("x"), new double[][]{{-5}}));
        assertThrows(IllegalArgumentException.class, () -> SeatAssigner.assign(one, List.of("x"), new double[][]{{Double.NaN}}));
    }

    @Test
    void bestFitRaisesTotalScoreWhereMaxFlowStopsAtTheSeatCount() {
        // Greedy (and max-flow warm-started from it) gives X the A seat (90) and Y the B seat (50): 140.
        // Swapping them fills the same 2 seats for 80 + 85 = 165.
        List<Role> roles = List.of(new Role("A", 1, 0), new Role("B", 1, 0));
        List<String> cands = List.of("X", "Y");
        double[][] scores = {{90, 80}, {85, 50}};

        Result flow = SeatAssigner.assign(roles, cands, scores);
        assertEquals(2, flow.seatsFilled());
        assertEquals(140, flow.totalScore(), 1e-9);

        Result best = SeatAssigner.bestFit(roles, cands, scores);
        assertValid(roles, cands, scores, best);
        assertEquals(2, best.seatsFilled());
        assertEquals(165, best.totalScore(), 1e-9);
        assertTrue(best.assignments().contains(new Assignment("A", "Y", 85)));
        assertTrue(best.assignments().contains(new Assignment("B", "X", 80)));
    }

    @Test
    void bestFitNeverTradesASeatForScore() {
        // One high-scoring pairing (X at A, 95) would leave B empty; filling both seats comes first.
        List<Role> roles = List.of(new Role("A", 1, 60), new Role("B", 1, 60));
        List<String> cands = List.of("X", "Y");
        double[][] scores = {{95, 61}, {62, 0}};
        Result best = SeatAssigner.bestFit(roles, cands, scores);
        assertEquals(2, best.seatsFilled());
        assertEquals(123, best.totalScore(), 1e-9);
    }

    @Test
    void bestFitMatchesBruteForceOnRandomDrives() {
        Random rnd = new Random(7);
        for (int t = 0; t < 400; t++) {
            int r = 1 + rnd.nextInt(3), c = rnd.nextInt(8);
            List<Role> roles = new ArrayList<>();
            for (int j = 0; j < r; j++) roles.add(new Role("r" + j, rnd.nextInt(4), 20 + rnd.nextInt(60)));
            List<String> cands = new ArrayList<>();
            double[][] scores = new double[c][r];
            for (int i = 0; i < c; i++) {
                cands.add("c" + i);
                for (int j = 0; j < r; j++) scores[i][j] = rnd.nextInt(101);
            }
            long[] optimum = bestSeatsThenScore(roles, scores);
            Result best = SeatAssigner.bestFit(roles, cands, scores);
            assertValid(roles, cands, scores, best);
            assertEquals(optimum[0], best.seatsFilled(), "seats, drive " + t);
            assertEquals(optimum[1], Math.round(best.totalScore()), "total score, drive " + t);
            assertEquals(best.seatsFilled(), best.augmentations());

            Result flow = SeatAssigner.assign(roles, cands, scores);
            assertEquals(flow.seatsFilled(), best.seatsFilled());
            assertTrue(best.totalScore() >= flow.totalScore() - 1e-9, "drive " + t);
        }
    }

    @Test
    void bestFitEdgeCases() {
        List<Role> one = List.of(new Role("A", 2, 50));
        assertEquals(0, SeatAssigner.bestFit(one, List.of(), new double[0][]).seatsFilled());
        assertEquals(0, SeatAssigner.bestFit(one, List.of("x"), new double[][]{{49.99}}).seatsFilled());
        Result zeroSeats = SeatAssigner.bestFit(List.of(new Role("A", 0, 0)), List.of("x"), new double[][]{{100}});
        assertEquals(0, zeroSeats.seatsFilled());
        assertEquals(0, zeroSeats.unfilled().get("A"));
    }

    /** {most seats, highest total score among assignments with that many seats}, by trying everything. */
    private static long[] bestSeatsThenScore(List<Role> roles, double[][] scores) {
        int[] left = roles.stream().mapToInt(Role::seats).toArray();
        return bestPair(0, roles, scores, left);
    }

    private static long[] bestPair(int i, List<Role> roles, double[][] scores, int[] left) {
        if (i == scores.length) return new long[]{0, 0};
        long[] b = bestPair(i + 1, roles, scores, left);
        for (int j = 0; j < roles.size(); j++) {
            if (left[j] > 0 && scores[i][j] >= roles.get(j).threshold()) {
                left[j]--;
                long[] rest = bestPair(i + 1, roles, scores, left);
                left[j]++;
                long seats = rest[0] + 1, score = rest[1] + (long) scores[i][j];
                if (seats > b[0] || (seats == b[0] && score > b[1])) b = new long[]{seats, score};
            }
        }
        return b;
    }

    private static void assertValid(List<Role> roles, List<String> cands, double[][] scores, Result res) {
        Set<String> placed = new HashSet<>();
        Map<String, Integer> filled = new HashMap<>();
        Map<String, Integer> roleIndex = new HashMap<>();
        for (int j = 0; j < roles.size(); j++) roleIndex.put(roles.get(j).id(), j);
        for (Assignment a : res.assignments()) {
            assertTrue(placed.add(a.candidateId()), "placed twice: " + a.candidateId());
            int j = roleIndex.get(a.roleId());
            int i = cands.indexOf(a.candidateId());
            assertEquals(scores[i][j], a.score());
            assertTrue(a.score() >= roles.get(j).threshold(), "below threshold: " + a);
            filled.merge(a.roleId(), 1, Integer::sum);
        }
        for (Role role : roles) {
            int f = filled.getOrDefault(role.id(), 0);
            assertTrue(f <= role.seats(), "over-filled " + role.id());
            assertEquals(role.seats() - f, res.unfilled().get(role.id()));
        }
    }

    /** Most seats fillable, by trying every role (or none) for every candidate. */
    private static int bruteForce(List<Role> roles, double[][] scores) {
        int[] left = roles.stream().mapToInt(Role::seats).toArray();
        return best(0, roles, scores, left);
    }

    private static int best(int i, List<Role> roles, double[][] scores, int[] left) {
        if (i == scores.length) return 0;
        int b = best(i + 1, roles, scores, left);
        for (int j = 0; j < roles.size(); j++) {
            if (left[j] > 0 && scores[i][j] >= roles.get(j).threshold()) {
                left[j]--;
                b = Math.max(b, 1 + best(i + 1, roles, scores, left));
                left[j]++;
            }
        }
        return b;
    }
}
