package dev.notch.engine.score;

import java.util.List;
import java.util.Objects;

/**
 * Turns a candidate's evidence for one skill into a score from 0 to 100 and an evidence level.
 *
 * <p>Each piece of evidence has a strength e in [0, 1] and is discounted by age with a 12-month
 * half-life: r = 0.5^(months / 12). The pieces are combined with a noisy-OR,
 * {@code score = 100 · (1 − Π (1 − e·r))}: more independent proof raises the score with
 * diminishing returns and order does not matter. A single repository is capped at
 * {@link #MAX_REPO_STRENGTH}, so one project alone scores at most 85: reaching the 90s takes
 * several independent pieces of work, which keeps strong candidates apart instead of tied at 100.
 * A repository whose author passed an Ownership Check (explained their own code live) is
 * <em>defended</em>: its cap rises to 1, so only work someone can defend reaches the very top.
 */
public final class SkillScoring {

    /** How much a skill claim is backed, weakest first. DEFENDED: verified work its author explained in an Ownership Check. */
    public enum Level { CLAIMED, SHOWN, VERIFIED, DEFENDED }

    public enum Kind { GITHUB_REPO, PROJECT, CHAT_EXPORT }

    /** Commits by the candidate at which a repo reaches full commit strength. */
    public static final int FULL_COMMITS = 50;
    /** Distinct weeks with commits at which a repo reaches full consistency strength. */
    public static final int FULL_WEEKS = 12;
    /** The most one repository can contribute: one project alone never scores a skill above 85. */
    public static final double MAX_REPO_STRENGTH = 0.85;
    /** The cap for a repository its author defended in an Ownership Check. */
    public static final double MAX_DEFENDED_STRENGTH = 1.0;
    /** Minimum own commits and active weeks for a repo to verify a skill. */
    public static final int VERIFY_COMMITS = 5;
    public static final int VERIFY_WEEKS = 2;
    public static final double PROJECT_STRENGTH = 0.4;
    public static final double CHAT_MAX_STRENGTH = 0.2;
    public static final double HALF_LIFE_MONTHS = 12;

    /**
     * One piece of proof for a skill. Build with {@link #repo}, {@link #project} or {@link #chat}.
     *
     * @param monthsSinceActive months since the evidence was last active (0 = this month)
     * @param chatStrength      for chat exports only: how deep the conversations went, 0 to 1
     */
    public record Evidence(Kind kind, int ownCommits, int activeWeeks, boolean fork, boolean duplicate,
                           double monthsSinceActive, double chatStrength, boolean defended) {
        public Evidence {
            Objects.requireNonNull(kind, "kind");
            if (ownCommits < 0 || activeWeeks < 0) throw new IllegalArgumentException("counts must not be negative");
            if (!(monthsSinceActive >= 0)) throw new IllegalArgumentException("monthsSinceActive must be >= 0");
            if (!(chatStrength >= 0 && chatStrength <= 1)) throw new IllegalArgumentException("chatStrength must be in [0, 1]");
            if (defended && kind != Kind.GITHUB_REPO) throw new IllegalArgumentException("only a repository can be defended");
        }

        public Evidence(Kind kind, int ownCommits, int activeWeeks, boolean fork, boolean duplicate,
                        double monthsSinceActive, double chatStrength) {
            this(kind, ownCommits, activeWeeks, fork, duplicate, monthsSinceActive, chatStrength, false);
        }

        public static Evidence repo(int ownCommits, int activeWeeks, boolean fork, boolean duplicate, double months) {
            return new Evidence(Kind.GITHUB_REPO, ownCommits, activeWeeks, fork, duplicate, months, 0, false);
        }

        /** A repository whose author passed an Ownership Check on it. */
        public static Evidence defendedRepo(int ownCommits, int activeWeeks, double months) {
            return new Evidence(Kind.GITHUB_REPO, ownCommits, activeWeeks, false, false, months, 0, true);
        }

        public static Evidence project(double months) {
            return new Evidence(Kind.PROJECT, 0, 0, false, false, months, 0);
        }

        public static Evidence chat(double depth, double months) {
            return new Evidence(Kind.CHAT_EXPORT, 0, 0, false, false, months, depth);
        }

        /** Strength before the age discount, in [0, 1]. */
        public double strength() {
            return switch (kind) {
                case GITHUB_REPO -> (fork || duplicate) ? 0
                        : (defended ? MAX_DEFENDED_STRENGTH : MAX_REPO_STRENGTH)
                        * (0.5 * Math.min(1.0, (double) ownCommits / FULL_COMMITS)
                        + 0.5 * Math.min(1.0, (double) activeWeeks / FULL_WEEKS));
                case PROJECT -> PROJECT_STRENGTH;
                case CHAT_EXPORT -> CHAT_MAX_STRENGTH * chatStrength;
            };
        }

        /** Whether this piece alone is enough to mark the skill verified. */
        public boolean verifies() {
            return kind == Kind.GITHUB_REPO && !fork && !duplicate
                    && ownCommits >= VERIFY_COMMITS && activeWeeks >= VERIFY_WEEKS;
        }
    }

    private SkillScoring() {
    }

    /** Age discount with a 12-month half-life. */
    public static double recency(double months) {
        if (!(months >= 0)) throw new IllegalArgumentException("months must be >= 0");
        return Math.pow(0.5, months / HALF_LIFE_MONTHS);
    }

    /** Skill score from 0 to 100. No evidence scores 0. */
    public static double score(List<Evidence> evidence) {
        Objects.requireNonNull(evidence, "evidence");
        double miss = 1.0;
        for (Evidence e : evidence) miss *= 1.0 - e.strength() * recency(e.monthsSinceActive());
        return 100.0 * (1.0 - miss);
    }

    /**
     * Defended if a piece that verifies was also defended; verified if any piece verifies; shown if
     * any piece adds strength; otherwise claimed.
     */
    public static Level level(List<Evidence> evidence) {
        Objects.requireNonNull(evidence, "evidence");
        Level best = Level.CLAIMED;
        for (Evidence e : evidence) {
            Level l = e.verifies() ? (e.defended() ? Level.DEFENDED : Level.VERIFIED) : e.strength() > 0 ? Level.SHOWN : Level.CLAIMED;
            if (l.compareTo(best) > 0) best = l;
        }
        return best;
    }
}
