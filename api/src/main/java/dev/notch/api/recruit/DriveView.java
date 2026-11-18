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

    public record Candidate(UUID userId, String handle, String headline, double score, List<Term> breakdown) {
    }

    /** @param poolSize public candidates searched; @param eligible those who meet every must-have */
    public record Shortlist(UUID roleId, String role, int poolSize, int eligible, List<Candidate> candidates) {
    }

    public record Placement(UUID userId, String handle, double score) {
    }

    /** @param eligible candidates at or above the role's threshold; null for a stored run */
    public record RoleResult(UUID roleId, String name, int seats, int filled, Integer eligible, List<Placement> placements) {
    }

    /**
     * A seat-assignment run.
     *
     * @param greedyFilled    seats a best-score-first greedy pass would have filled, for comparison
     * @param augmentingPaths paths max-flow found beyond the greedy start
     */
    public record Assignment(Instant runAt, int totalSeats, int filled, int greedyFilled, int augmentingPaths,
                             double millis, List<RoleResult> roles) {
    }
}
