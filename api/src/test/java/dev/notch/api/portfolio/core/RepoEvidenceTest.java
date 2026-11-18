package dev.notch.api.portfolio.core;

import dev.notch.api.portfolio.core.RepoEvidence.Draft;
import dev.notch.api.portfolio.core.RepoEvidence.RepoData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepoEvidenceTest {

    private static final SkillCatalog CAT = SkillCatalogTest.CATALOG;
    private static final Instant MON = Instant.parse("2026-11-02T10:00:00Z"); // a Monday

    private static RepoData repo(boolean fork, List<Instant> commits, Map<String, Long> langs, List<String> topics) {
        return new RepoData("asha/sales-dashboard", "https://github.com/asha/sales-dashboard",
                "Sales analysis with pandas and SQL", topics, langs, fork, commits, MON, "root1", "tree1");
    }

    @Test
    void buildsDraftFromOwnCommitsLanguagesTopicsAndText() {
        List<Instant> commits = List.of(MON, MON.plusSeconds(86_400), MON.plusSeconds(7 * 86_400), MON.plusSeconds(30 * 86_400));
        Draft d = RepoEvidence.build(repo(false, commits, Map.of("Python", 9000L, "C++", 50L), List.of("machine-learning")), CAT);
        assertEquals("sales-dashboard", d.title());
        assertEquals(4, d.ownCommits());
        assertEquals(3, d.activeWeeks());
        assertEquals(MON.plusSeconds(30 * 86_400), d.lastActive());
        // Python (main language), ML (topic), pandas and SQL (description); C++ is under 10% of the code.
        assertEquals(Set.of(1, 3, 4, 2), d.skillIds());
        assertEquals("root1", d.rootSha());
    }

    @Test
    void edgeCaseForksAndReposWithoutOwnCommitsAreSkipped() {
        assertEquals(RepoEvidence.Skip.FORK, RepoEvidence.skip(repo(true, List.of(MON), Map.of(), List.of())).orElseThrow());
        assertEquals(RepoEvidence.Skip.NO_OWN_COMMITS, RepoEvidence.skip(repo(false, List.of(), Map.of(), List.of())).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> RepoEvidence.build(repo(true, List.of(MON), Map.of(), List.of()), CAT));
    }

    @Test
    void activeWeeksCountsIsoWeeksAcrossYearEnd() {
        // 2026-12-31 (Thu) and 2027-01-01 (Fri) fall in the same ISO week, 2026-W53.
        assertEquals(1, RepoEvidence.activeWeeks(List.of(Instant.parse("2026-12-31T12:00:00Z"), Instant.parse("2027-01-01T12:00:00Z"))));
        assertEquals(0, RepoEvidence.activeWeeks(List.of()));
    }

    @Test
    void readsLastPageFromLinkHeader() {
        String link = "<https://api.github.com/repositories/1/commits?per_page=1&page=2>; rel=\"next\", "
                + "<https://api.github.com/repositories/1/commits?per_page=1&page=347>; rel=\"last\"";
        assertEquals(OptionalInt.of(347), RepoEvidence.lastPage(link));
        assertEquals(OptionalInt.empty(), RepoEvidence.lastPage(null));
        assertEquals(OptionalInt.empty(), RepoEvidence.lastPage("<https://x?page=2>; rel=\"next\""));
    }

    @Test
    void edgeCaseNoLanguagesStillReadsNameAndDescription() {
        Draft d = RepoEvidence.build(new RepoData("ravi/nodejs-api", "https://github.com/ravi/nodejs-api", null,
                null, null, false, List.of(MON), MON, null, null), CAT);
        assertEquals(Set.of(6), d.skillIds());
        assertNull(d.description());
    }
}
