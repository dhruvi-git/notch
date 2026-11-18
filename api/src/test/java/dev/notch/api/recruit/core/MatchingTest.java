package dev.notch.api.recruit.core;

import dev.notch.api.recruit.core.Matching.Candidate;
import dev.notch.api.recruit.core.Matching.DriveResult;
import dev.notch.api.recruit.core.Matching.Ranked;
import dev.notch.api.recruit.core.Matching.RoleSpec;
import dev.notch.engine.score.MatchScoring.CandidateSkill;
import dev.notch.engine.score.MatchScoring.Requirement;
import dev.notch.engine.score.SkillScoring.Level;
import dev.notch.engine.select.RandomizedSelect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchingTest {

    private static Candidate cand(String handle, Map<String, CandidateSkill> skills) {
        return new Candidate(UUID.nameUUIDFromBytes(handle.getBytes()), handle, skills);
    }

    private static CandidateSkill v(double s) {
        return new CandidateSkill(s, Level.VERIFIED);
    }

    private static final List<Requirement> DATA = List.of(
            new Requirement("SQL", 3, true, Level.VERIFIED), new Requirement("Python", 1, false, Level.SHOWN));
    private static final List<Requirement> BACKEND = List.of(new Requirement("Java", 1, true, Level.SHOWN));

    @Test
    void shortlistRanksEligibleCandidatesBestFirst() {
        List<Candidate> pool = List.of(
                cand("asha", Map.of("SQL", v(90), "Python", v(80))),  // 87.5
                cand("ben", Map.of("SQL", v(60))),                     // 45
                cand("chen", Map.of("Python", v(100))),                // no SQL: ineligible
                cand("dev", Map.of("SQL", v(70), "Python", v(70))));  // 70
        List<Ranked> top = Matching.shortlist(DATA, pool, 2, new RandomizedSelect(1));
        assertEquals(List.of("asha", "dev"), top.stream().map(Ranked::handle).toList());
        assertEquals(87.5, top.get(0).match().score(), 1e-9);
    }

    @Test
    void shortlistTiesGoToEarlierHandle() {
        List<Candidate> pool = List.of(cand("zoe", Map.of("SQL", v(50))), cand("amy", Map.of("SQL", v(50))));
        assertEquals("amy", Matching.shortlist(DATA, pool, 1, new RandomizedSelect(3)).get(0).handle());
    }

    @Test
    void edgeCaseEmptyPoolGivesEmptyShortlist() {
        assertEquals(List.of(), Matching.shortlist(DATA, List.of(), 5, new RandomizedSelect(1)));
    }

    @Test
    void driveUsesMaxFlowAndReportsGreedyForComparison() {
        // One data seat, one backend seat. The all-rounder is the best data match but the only Java
        // candidate; greedy gives them the data seat and leaves backend empty.
        UUID data = UUID.randomUUID(), backend = UUID.randomUUID();
        List<RoleSpec> roles = List.of(new RoleSpec(data, "Data analyst", 1, 50, DATA),
                new RoleSpec(backend, "Backend", 1, 50, BACKEND));
        Candidate allRounder = cand("ria", Map.of("SQL", v(95), "Python", v(95), "Java", v(60)));
        Candidate analyst = cand("sam", Map.of("SQL", v(70)));
        DriveResult r = Matching.assign(roles, List.of(allRounder, analyst));

        assertEquals(2, r.filled());
        assertEquals(1, r.greedyFilled());
        assertEquals(2, r.totalSeats());
        assertTrue(r.placements().contains(new Matching.Placement(backend, allRounder.userId(), "ria", 60)));
        assertEquals(List.of(2, 1), r.roles().stream().map(Matching.RoleOutcome::eligible).toList());
    }

    @Test
    void edgeCaseNobodyEligibleFillsNothing() {
        UUID backend = UUID.randomUUID();
        DriveResult r = Matching.assign(List.of(new RoleSpec(backend, "Backend", 3, 50, BACKEND)),
                List.of(cand("sam", Map.of("SQL", v(70)))));
        assertEquals(0, r.filled());
        assertEquals(0, r.roles().get(0).eligible());
        assertEquals(3, r.roles().get(0).seats());
    }

    @Test
    void driveOfManyCandidatesIsValid() {
        List<Candidate> pool = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 500; i++) {
            pool.add(cand("c" + i, Map.of("SQL", v(rnd.nextInt(101)), "Python", v(rnd.nextInt(101)),
                    "Java", v(rnd.nextInt(101)))));
        }
        List<RoleSpec> roles = List.of(new RoleSpec(UUID.randomUUID(), "Data", 40, 60, DATA),
                new RoleSpec(UUID.randomUUID(), "Backend", 40, 60, BACKEND));
        DriveResult r = Matching.assign(roles, pool);
        assertTrue(r.filled() >= r.greedyFilled());
        assertEquals(r.filled(), r.placements().stream().map(Matching.Placement::userId).distinct().count());
        assertTrue(r.placements().stream().allMatch(p -> p.score() >= 60));
    }
}
