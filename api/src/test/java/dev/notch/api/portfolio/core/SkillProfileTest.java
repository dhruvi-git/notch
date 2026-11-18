package dev.notch.api.portfolio.core;

import dev.notch.api.portfolio.core.SkillProfile.EvidenceItem;
import dev.notch.api.portfolio.core.SkillProfile.Standing;
import dev.notch.engine.score.SkillScoring.Kind;
import dev.notch.engine.score.SkillScoring.Level;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SkillProfileTest {

    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");
    private static final SkillCatalog CAT = SkillCatalogTest.CATALOG;

    private static EvidenceItem repo(int commits, int weeks, boolean fork, Instant last, Integer... skills) {
        return new EvidenceItem(UUID.randomUUID(), Kind.GITHUB_REPO, "repo", "https://github.com/x/y", commits, weeks,
                fork, false, last, 0, Set.of(skills));
    }

    @Test
    void scoresLevelsAndEvidenceLinksPerSkill() {
        EvidenceItem strong = repo(30, 8, false, NOW, 1, 2);
        EvidenceItem project = new EvidenceItem(UUID.randomUUID(), Kind.PROJECT, "dashboard", null, 0, 0, false, false,
                NOW, 0, Set.of(2, 8));
        List<Standing> s = SkillProfile.assemble(CAT, List.of(strong, project), Set.of(), Set.of(), NOW);

        Standing sql = s.stream().filter(x -> x.skill().id() == 2).findFirst().orElseThrow();
        assertEquals(100.0, sql.score(), 1e-9); // a full-strength repo alone reaches 100
        assertEquals(Level.VERIFIED, sql.level());
        assertEquals(2, sql.evidenceIds().size());

        Standing bi = s.stream().filter(x -> x.skill().id() == 8).findFirst().orElseThrow();
        assertEquals(40.0, bi.score(), 1e-9);
        assertEquals(Level.SHOWN, bi.level());
        assertEquals(s.get(s.size() - 1), bi); // sorted best first
    }

    @Test
    void edgeCaseClaimedOnlySkillShowsAsClaimedWithZero() {
        List<Standing> s = SkillProfile.assemble(CAT, List.of(), Set.of(5), Set.of(), NOW);
        assertEquals(1, s.size());
        assertEquals(Level.CLAIMED, s.get(0).level());
        assertEquals(0, s.get(0).score(), 1e-9);
        assertEquals(List.of(), s.get(0).evidenceIds());
    }

    @Test
    void forksDoNotCountAsEvidence() {
        List<Standing> s = SkillProfile.assemble(CAT, List.of(repo(100, 20, true, NOW, 1)), Set.of(), Set.of(), NOW);
        assertEquals(Level.CLAIMED, s.get(0).level());
        assertEquals(List.of(), s.get(0).evidenceIds());
    }

    @Test
    void oldEvidenceDecaysWithAOneYearHalfLife() {
        Instant yearAgo = NOW.minus(Duration.ofHours(Math.round(12 * 30.44 * 24)));
        EvidenceItem p = new EvidenceItem(UUID.randomUUID(), Kind.PROJECT, "old", null, 0, 0, false, false, yearAgo, 0, Set.of(1));
        assertEquals(20.0, SkillProfile.assemble(CAT, List.of(p), Set.of(), Set.of(), NOW).get(0).score(), 0.1);
        // Evidence dated in the future (clock skew) counts as current, not as a bonus.
        EvidenceItem future = new EvidenceItem(UUID.randomUUID(), Kind.PROJECT, "f", null, 0, 0, false, false,
                NOW.plusSeconds(3600), 0, Set.of(1));
        assertEquals(40.0, SkillProfile.assemble(CAT, List.of(future), Set.of(), Set.of(), NOW).get(0).score(), 1e-9);
    }

    @Test
    void hiddenAndUnknownSkillsAreLeftOut() {
        List<Standing> s = SkillProfile.assemble(CAT, List.of(repo(10, 3, false, NOW, 1, 2, 999)), Set.of(), Set.of(2), NOW);
        assertEquals(List.of(1), s.stream().map(x -> x.skill().id()).toList());
    }
}
