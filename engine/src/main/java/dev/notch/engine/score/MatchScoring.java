package dev.notch.engine.score;

import dev.notch.engine.score.SkillScoring.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Scores how well a candidate fits a role, from 0 to 100, as a weighted average of the
 * candidate's skill scores over the role's requirements:
 * {@code match = Σ w·s·g / Σ w}, where g is 1 when the candidate's evidence level meets the
 * requirement's minimum and 0.5 when it does not. A missing must-have skill makes the
 * candidate ineligible (match 0). Every term is returned so a recruiter can see the breakdown.
 */
public final class MatchScoring {

    public record Requirement(String skill, double weight, boolean mustHave, Level minLevel) {
        public Requirement {
            Objects.requireNonNull(skill, "skill");
            Objects.requireNonNull(minLevel, "minLevel");
            if (!(weight > 0)) throw new IllegalArgumentException("weight must be positive for " + skill);
        }
    }

    /** A candidate's standing in one skill. */
    public record CandidateSkill(double score, Level level) {
        public CandidateSkill {
            Objects.requireNonNull(level, "level");
            if (!(score >= 0 && score <= 100)) throw new IllegalArgumentException("score must be in [0, 100]");
        }
    }

    /** One requirement's share of the match: {@code contribution = weight · score · levelFactor / Σ weight}. */
    public record Term(String skill, double weight, double skillScore, Level level, double levelFactor,
                       double contribution) {
    }

    public record Match(double score, boolean eligible, List<Term> terms, List<String> missingMustHaves) {
    }

    /** Factor applied when the candidate's level is below the requirement's minimum. */
    public static final double BELOW_LEVEL_FACTOR = 0.5;

    private MatchScoring() {
    }

    public static Match match(List<Requirement> requirements, Map<String, CandidateSkill> skills) {
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(skills, "skills");
        if (requirements.isEmpty()) throw new IllegalArgumentException("a role needs at least one requirement");
        Set<String> seen = new HashSet<>();
        double totalWeight = 0;
        for (Requirement r : requirements) {
            if (!seen.add(r.skill())) throw new IllegalArgumentException("duplicate requirement: " + r.skill());
            totalWeight += r.weight();
        }

        List<Term> terms = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        double sum = 0;
        for (Requirement r : requirements) {
            CandidateSkill have = skills.get(r.skill());
            if (have == null || have.score() == 0) {
                if (r.mustHave()) missing.add(r.skill());
                terms.add(new Term(r.skill(), r.weight(), 0, have == null ? null : have.level(), 0, 0));
                continue;
            }
            double g = have.level().compareTo(r.minLevel()) >= 0 ? 1.0 : BELOW_LEVEL_FACTOR;
            double contribution = r.weight() * have.score() * g / totalWeight;
            sum += contribution;
            terms.add(new Term(r.skill(), r.weight(), have.score(), have.level(), g, contribution));
        }
        boolean eligible = missing.isEmpty();
        return new Match(eligible ? sum : 0, eligible, List.copyOf(terms), List.copyOf(missing));
    }

    /**
     * The smallest single change that lifts a candidate to a role's minimum match.
     *
     * @param skill    the skill to improve
     * @param from     its score now (0 when the candidate has none)
     * @param to       the score it needs, at the role's evidence level for it
     * @param minLevel the evidence level the role asks for on this skill
     * @param levelUp  whether the candidate's evidence is below that level now
     */
    public record Fix(String skill, double from, double to, Level minLevel, boolean levelUp) {
        /** Score points to gain: 0 when reaching the evidence level is enough. */
        public double raise() {
            return to - from;
        }
    }

    /**
     * The one requirement a candidate can improve, alone, to reach {@code threshold}, choosing
     * the smallest raise in skill score (then preferring no change of evidence level).
     *
     * <p>The match is linear in each skill: with the other terms fixed at {@code others}, skill
     * {@code i} reaches the threshold at the required level when
     * {@code w_i · s / W ≥ threshold − others}, so {@code s = (threshold − others) · W / w_i}.
     * A skill needing more than 100 can't do it alone. A candidate missing one must-have can
     * only be fixed on that skill; one missing two or more has no single fix. Already at or above
     * the threshold: empty.
     */
    public static Optional<Fix> closestFix(List<Requirement> requirements, Map<String, CandidateSkill> skills,
                                           double threshold) {
        Match now = match(requirements, skills);
        if (now.missingMustHaves().size() > 1) return Optional.empty();
        if (now.eligible() && now.score() >= threshold) return Optional.empty();
        double totalWeight = requirements.stream().mapToDouble(Requirement::weight).sum();
        double all = now.terms().stream().mapToDouble(Term::contribution).sum();

        Fix best = null;
        for (int i = 0; i < requirements.size(); i++) {
            Requirement r = requirements.get(i);
            if (!now.missingMustHaves().isEmpty() && !now.missingMustHaves().contains(r.skill())) continue;
            Term t = now.terms().get(i);
            double others = all - t.contribution();
            double needed = Math.max(0, (threshold - others) * totalWeight / r.weight());
            if (needed > 100 + 1e-9) continue;
            CandidateSkill have = skills.get(r.skill());
            double from = have == null ? 0 : have.score();
            boolean belowLevel = have == null || have.score() == 0 || have.level().compareTo(r.minLevel()) < 0;
            // A skill at 0 counts as absent, so evidence of a missing one has to reach at least 1.
            double to = Math.min(100, Math.max(Math.max(from, 1), Math.ceil(needed - 1e-9)));
            if (!belowLevel && to <= from) continue; // this skill is not what holds the candidate back
            Fix fix = new Fix(r.skill(), from, to, r.minLevel(), belowLevel);
            if (best == null || fix.raise() < best.raise() - 1e-9
                    || (Math.abs(fix.raise() - best.raise()) <= 1e-9 && best.levelUp() && !fix.levelUp())) {
                best = fix;
            }
        }
        return Optional.ofNullable(best);
    }
}
