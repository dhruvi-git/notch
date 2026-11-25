package dev.notch.api.recruit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the recruiter endpoints return. */
public final class DriveView {

    private DriveView() {
    }

    public record Requirement(int skillId, String skill, double weight, boolean mustHave, String minLevel) {
    }

    public record Role(UUID id, String name, int seats, double threshold, List<Requirement> requirements) {
    }

    public record Drive(UUID id, String title, String description, Instant createdAt, List<Role> roles) {
    }

    public record Summary(UUID id, String title, Instant createdAt) {
    }

    /** One requirement's share of a match score, for the breakdown next to each candidate. */
    public record Term(String skill, double weight, double skillScore, String level, double levelFactor,
                       double contribution) {
    }

    /**
     * The smallest single change that would lift a candidate to the role's minimum match.
     *
     * @param level   the evidence level the role asks for on this skill
     * @param levelUp whether the candidate's evidence on it is below that level now
     */
    public record Fix(String skill, double from, double to, String level, boolean levelUp) {
    }

    /** @param fix what would lift a candidate below the role's minimum match over it; null when none is needed or possible */
    public record Candidate(UUID userId, String handle, String headline, double score, List<Term> breakdown, Fix fix) {
    }

    /** A candidate missing one must-have whom one improvement would bring in. */
    public record NearMiss(UUID userId, String handle, String headline, Fix fix) {
    }

    /**
     * @param poolSize   public candidates searched
     * @param eligible   those who meet every must-have
     * @param threshold  the role's minimum match
     * @param nearMisses up to five candidates missing one must-have, smallest fix first
     */
    public record Shortlist(UUID roleId, String role, int poolSize, int eligible, double threshold,
                            List<Candidate> candidates, List<NearMiss> nearMisses) {
    }

    /** @param moved whether best-score-first greedy would have left this person out or put them in another role */
    public record Placement(UUID userId, String handle, double score, boolean moved) {
    }

    /** @param eligible candidates at or above the role's threshold; null for a stored run */
    public record RoleResult(UUID roleId, String name, int seats, int filled, Integer eligible, List<Placement> placements) {
    }

    /**
     * A seat-assignment run, as recruiters see it: the outcome, not the method.
     *
     * @param totalMatch     sum of the placed candidates' match scores; null for runs stored before it was kept
     * @param baselineFilled seats that handing each one to the top scorer still free would fill, for comparison
     * @param baselineMatch  total match of that baseline; null for older runs
     */
    public record Assignment(Instant runAt, int totalSeats, int filled, Double totalMatch, int baselineFilled,
                             Double baselineMatch, List<RoleResult> roles) {
    }
}
