package dev.notch.api.portfolio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A portfolio as the API returns it. The owner's view ({@code GET /api/portfolio/me}) includes
 * skills from chat exports still waiting for approval; the public view never does.
 *
 * @param hasProfile false until the candidate picks a handle; the other profile fields are then null
 */
public record ProfileView(boolean hasProfile, String handle, String headline, String domain, boolean isPublic,
                          List<SkillView> skills, List<EvidenceView> evidence, int poolSize) {

    /**
     * @param topPercent the tightest badge band reached (1, 5, 10 or 25), or null for none
     * @param evidenceIds the evidence that backs this skill, to link each skill to its proof
     */
    public record SkillView(int id, String name, String domain, double score, String level, Integer topPercent,
                            List<UUID> evidenceIds) {
    }

    public record SkillRef(int id, String name) {
    }

    /**
     * @param pendingSkills skills proposed from a chat export, not yet approved (owner's view only)
     * @param ownership     the best finished Ownership Check on this repository, or null
     */
    public record EvidenceView(UUID id, String kind, String title, String url, String description, int ownCommits,
                               int activeWeeks, boolean fork, boolean duplicate, Instant lastActive,
                               List<SkillRef> skills, List<SkillRef> pendingSkills, OwnershipView ownership) {
    }

    public record OwnershipView(UUID checkId, int score, boolean defended, Instant finishedAt, int attempts) {
    }
}
