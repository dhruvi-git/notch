package dev.notch.api.portfolio.core;

import dev.notch.engine.score.SkillScoring;
import dev.notch.engine.score.SkillScoring.Evidence;
import dev.notch.engine.score.SkillScoring.Level;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a candidate's skill list from their evidence: for every skill they claimed or that
 * some evidence backs, the score, the evidence level and the ids of the evidence behind it.
 * Scores are recomputed on every read, so they always reflect the newest evidence and age.
 */
public final class SkillProfile {

    /** One piece of evidence as stored, with the skills it backs (only approved links). */
    public record EvidenceItem(UUID id, SkillScoring.Kind kind, String title, String url, int ownCommits,
                               int activeWeeks, boolean fork, boolean duplicate, Instant lastActive,
                               double chatDepth, Set<Integer> skillIds) {
        public EvidenceItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(lastActive, "lastActive");
            skillIds = Set.copyOf(skillIds);
        }

        Evidence toScoring(Instant now) {
            double months = monthsBetween(lastActive, now);
            return switch (kind) {
                case GITHUB_REPO -> Evidence.repo(ownCommits, activeWeeks, fork, duplicate, months);
                case PROJECT -> Evidence.project(months);
                case CHAT_EXPORT -> Evidence.chat(chatDepth, months);
            };
        }
    }

    public record Standing(SkillCatalog.Skill skill, double score, Level level, List<UUID> evidenceIds) {
    }

    private static final double DAYS_PER_MONTH = 30.44;

    private SkillProfile() {
    }

    /**
     * @param claimed skill ids the candidate typed in themselves (shown as claimed when nothing backs them)
     * @param hidden  skill ids the candidate chose not to show
     */
    public static List<Standing> assemble(SkillCatalog catalog, List<EvidenceItem> evidence, Set<Integer> claimed,
                                          Set<Integer> hidden, Instant now) {
        Map<Integer, List<EvidenceItem>> bySkill = new LinkedHashMap<>();
        for (int id : claimed) bySkill.computeIfAbsent(id, k -> new ArrayList<>());
        for (EvidenceItem e : evidence) {
            for (int id : e.skillIds()) bySkill.computeIfAbsent(id, k -> new ArrayList<>()).add(e);
        }
        List<Standing> out = new ArrayList<>();
        for (Map.Entry<Integer, List<EvidenceItem>> entry : bySkill.entrySet()) {
            if (hidden.contains(entry.getKey())) continue;
            SkillCatalog.Skill skill = catalog.get(entry.getKey()).orElse(null);
            if (skill == null) continue; // removed from the catalogue
            List<Evidence> scoring = entry.getValue().stream().map(e -> e.toScoring(now)).toList();
            List<UUID> ids = entry.getValue().stream()
                    .filter(e -> e.toScoring(now).strength() > 0).map(EvidenceItem::id).toList();
            out.add(new Standing(skill, round1(SkillScoring.score(scoring)), SkillScoring.level(scoring), ids));
        }
        out.sort(Comparator.comparingDouble(Standing::score).reversed()
                .thenComparing(s -> s.skill().name()));
        return out;
    }

    static double monthsBetween(Instant then, Instant now) {
        if (!then.isBefore(now)) return 0;
        return Duration.between(then, now).toHours() / 24.0 / DAYS_PER_MONTH;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
