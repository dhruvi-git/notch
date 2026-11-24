package dev.notch.api.recruit.core;

import dev.notch.engine.flow.SeatAssigner;
import dev.notch.engine.score.MatchScoring;
import dev.notch.engine.score.MatchScoring.CandidateSkill;
import dev.notch.engine.score.MatchScoring.Match;
import dev.notch.engine.score.MatchScoring.Requirement;
import dev.notch.engine.select.RandomizedSelect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The recruiter side's two computations over a pool of candidate profiles: a ranked shortlist
 * for one role (match scores, then the engine's randomized top-k) and a seat assignment for a
 * whole drive (match scores for every role, then min-cost flow for the best fit, with max-flow
 * and best-score-first greedy run alongside for the comparison shown to the recruiter).
 */
public final class Matching {

    /** A candidate's skills, keyed by skill name as in {@link Requirement#skill()}. */
    public record Candidate(UUID userId, String handle, Map<String, CandidateSkill> skills) {
        public Candidate {
            Objects.requireNonNull(userId, "userId");
            skills = Map.copyOf(skills);
        }
    }

    public record Ranked(UUID userId, String handle, Match match) {
    }

    public record RoleSpec(UUID roleId, String name, int seats, double threshold, List<Requirement> requirements) {
    }

    /** @param moved whether best-score-first greedy would have left this person out or put them in another role */
    public record Placement(UUID roleId, UUID userId, String handle, double score, boolean moved) {
    }

    public record RoleOutcome(UUID roleId, String name, int seats, int filled, int eligible) {
    }

    /**
     * A drive's best-fit assignment, plus what max-flow and greedy would have done.
     *
     * @param augmentingPaths paths max-flow found beyond the greedy start
     * @param millis          max-flow's running time
     * @param bestFitMillis   min-cost flow's running time
     */
    public record DriveResult(List<Placement> placements, List<RoleOutcome> roles, int totalSeats,
                              int greedyFilled, double greedyScore, int maxFlowFilled, double maxFlowScore,
                              double bestFitScore, int augmentingPaths, double millis, double bestFitMillis) {
        public int filled() {
            return placements.size();
        }
    }

    private Matching() {
    }

    /** Eligible candidates for a role, best first, at most {@code k}. Ties go to the earlier handle. */
    public static List<Ranked> shortlist(List<Requirement> requirements, List<Candidate> pool, int k,
                                         RandomizedSelect select) {
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        List<Ranked> eligible = new ArrayList<>();
        for (Candidate c : pool) {
            Match m = MatchScoring.match(requirements, c.skills());
            if (m.eligible() && m.score() > 0) eligible.add(new Ranked(c.userId(), c.handle(), m));
        }
        Comparator<Ranked> order = Comparator.<Ranked>comparingDouble(r -> r.match().score())
                .thenComparing(Ranked::handle, Comparator.reverseOrder());
        return select.topK(eligible, k, order);
    }

    public static DriveResult assign(List<RoleSpec> roles, List<Candidate> pool) {
        if (roles.isEmpty()) throw new IllegalArgumentException("a drive needs at least one role");
        double[][] scores = new double[pool.size()][roles.size()];
        for (int i = 0; i < pool.size(); i++) {
            for (int j = 0; j < roles.size(); j++) {
                Match m = MatchScoring.match(roles.get(j).requirements(), pool.get(i).skills());
                scores[i][j] = Math.min(100, Math.max(0, m.score()));
            }
        }
        List<SeatAssigner.Role> seatRoles = new ArrayList<>();
        for (RoleSpec r : roles) seatRoles.add(new SeatAssigner.Role(r.roleId().toString(), r.seats(), r.threshold()));
        List<String> ids = new ArrayList<>();
        Map<String, Candidate> byId = new HashMap<>();
        for (Candidate c : pool) {
            ids.add(c.userId().toString());
            byId.put(c.userId().toString(), c);
        }

        long start = System.nanoTime();
        SeatAssigner.Result flow = SeatAssigner.assign(seatRoles, ids, scores);
        double millis = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        SeatAssigner.Result best = SeatAssigner.bestFit(seatRoles, ids, scores);
        double bestMillis = (System.nanoTime() - start) / 1e6;
        SeatAssigner.Result greedy = SeatAssigner.greedy(seatRoles, ids, scores);

        Map<String, String> greedyRole = new HashMap<>();
        for (SeatAssigner.Assignment a : greedy.assignments()) greedyRole.put(a.candidateId(), a.roleId());
        List<Placement> placements = new ArrayList<>();
        Map<String, Integer> filled = new HashMap<>();
        for (SeatAssigner.Assignment a : best.assignments()) {
            Candidate c = byId.get(a.candidateId());
            boolean moved = !a.roleId().equals(greedyRole.get(a.candidateId()));
            placements.add(new Placement(UUID.fromString(a.roleId()), c.userId(), c.handle(), a.score(), moved));
            filled.merge(a.roleId(), 1, Integer::sum);
        }
        List<RoleOutcome> outcomes = new ArrayList<>();
        for (int j = 0; j < roles.size(); j++) {
            RoleSpec r = roles.get(j);
            int eligible = 0;
            for (double[] row : scores) if (row[j] >= r.threshold()) eligible++;
            outcomes.add(new RoleOutcome(r.roleId(), r.name(), r.seats(),
                    filled.getOrDefault(r.roleId().toString(), 0), eligible));
        }
        return new DriveResult(List.copyOf(placements), List.copyOf(outcomes), best.totalSeats(),
                greedy.seatsFilled(), greedy.totalScore(), flow.seatsFilled(), flow.totalScore(), best.totalScore(),
                flow.augmentations(), millis, bestMillis);
    }
}
