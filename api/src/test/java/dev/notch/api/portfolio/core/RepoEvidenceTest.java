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

    @Test
    void rhythmCountsCommitsByWeekdayAndHourInTheCandidatesZone() {
        // 2026-11-16 is a Monday. 18:00 UTC is 23:30 in India; 20:00 UTC on Sunday is 01:30 on Monday there.
        List<Instant> commits = List.of(Instant.parse("2026-11-16T18:00:00Z"), Instant.parse("2026-11-15T20:00:00Z"),
                Instant.parse("2026-11-21T04:00:00Z"), Instant.parse("2026-11-10T09:00:00Z"));
        RepoEvidence.Rhythm r = RepoEvidence.rhythm(commits, RepoEvidence.DEFAULT_ZONE);
        assertEquals(Instant.parse("2026-11-10T09:00:00Z"), r.firstActive());
        assertArrayEquals(new int[]{2, 1, 0, 0, 0, 1, 0}, r.weekdays());   // Mon x2, Tue (14:30), Sat (09:30)
        assertEquals(1, r.hours()[23]);
        assertEquals(1, r.hours()[1]);
        assertEquals(1, r.hours()[9]);
        assertEquals(1, r.hours()[14]);
        assertEquals(4, java.util.Arrays.stream(r.hours()).sum());
        // In UTC the same commits fall on other days.
        assertArrayEquals(new int[]{1, 1, 0, 0, 0, 1, 1}, RepoEvidence.rhythm(commits, java.time.ZoneOffset.UTC).weekdays());
        // Edge: no commits, no rhythm; and the arrays can't be changed from outside.
        assertNull(RepoEvidence.rhythm(List.of(), RepoEvidence.DEFAULT_ZONE));
        int[] days = {1, 0, 0, 0, 0, 0, 0};
        RepoEvidence.Rhythm fixed = new RepoEvidence.Rhythm(Instant.EPOCH, days, new int[24]);
        days[0] = 9;
        assertEquals(1, fixed.weekdays()[0]);
        assertThrows(IllegalArgumentException.class, () -> new RepoEvidence.Rhythm(Instant.EPOCH, new int[6], new int[24]));
    }
}
