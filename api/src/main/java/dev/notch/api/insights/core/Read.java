package dev.notch.api.insights.core;

import java.time.Instant;
import java.util.List;

/**
 * A candidate's read: who they are as a builder, drawn only from their own evidence.
 *
 * @param saidDone null on the public read, and when there is no AI history and no work to compare
 * @param insights plain-English findings, strongest first (at most four)
 * @param rhythm   when the candidate's own commits happen, or null before any repository with commit times
 */
public record Read(Archetype archetype, Constellation constellation, SaidDone saidDone, List<Insight> insights, Stats stats,
                   Rhythm rhythm) {

    /** The public read leaves out AI history and the private insights. */
    public Read publicView() {
        return new Read(archetype, constellation, null, List.of(), stats, rhythm);
    }

    /**
     * @param reason why this archetype, in numbers ("62% of your proven skill is in data")
     * @param skills the skills that put the candidate here, strongest first
     * @param trait  how they work, or null when there is too little to tell
     */
    public record Archetype(String key, String name, String line, String reason, List<String> skills, Trait trait) {
    }

    public record Trait(String key, String name, String line) {
    }

    /** Skills as stars, and lines between skills used together in the same repository or project. */
    public record Constellation(List<Star> stars, List<Link> links) {
    }

    public record Star(int id, String name, String domain, double score, String level, Integer topPercent) {
    }

    /** @param weight how many repositories and projects use both skills */
    public record Link(int a, int b, int weight) {
    }

    /**
     * What the candidate's AI history says they're into, against what their public work shows.
     *
     * @param edges     learning edges: talked about, not built with yet
     * @param unproven  their AI remembers them using it, but nothing public shows it
     * @param strengths quiet strengths: built with a lot, never talked about
     * @param inSync    both
     * @param hasHistory false when no AI history was imported (the lists then only hold strengths)
     */
    public record SaidDone(List<Pair> edges, List<Pair> unproven, List<Pair> strengths, List<Pair> inSync, boolean hasHistory) {
    }

    public record Pair(int skillId, String name, String said, String done) {
    }

    public record Insight(String key, String title, String detail) {
    }

    /** @param since the earliest activity on Notch's evidence, or null */
    public record Stats(int repositories, int commits, int activeWeeks, int skills, int defended, Instant since) {
    }

    /** @param weekdays Monday first */
    public record Rhythm(int[] weekdays, int[] hours, int commits) {
    }
}
