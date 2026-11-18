package dev.notch.engine.score;

import dev.notch.engine.score.SkillScoring.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
}
