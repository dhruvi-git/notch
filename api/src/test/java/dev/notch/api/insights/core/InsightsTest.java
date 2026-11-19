package dev.notch.api.insights.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsTest {

    static final Instant NOW = Instant.parse("2026-11-19T06:00:00Z");
    static final Map<Integer, String> NAMES = Map.of(1, "Python", 17, "SQL", 20, "pandas", 30, "Power BI", 42, "scikit-learn",
            60, "React", 80, "Docker", 90, "Algorithms");

    static Insights.Skill skill(int id, String domain, double score, String level, Integer top) {
        return new Insights.Skill(id, NAMES.get(id), domain, score, level, top);
    }

    static Insights.Work repo(String title, int commits, int weeks, Instant first, Instant last, Set<Integer> skills,
                              int[] days, int[] hours) {
        return new Insights.Work(UUID.randomUUID(), "GITHUB_REPO", title, commits, weeks, false, false, first, last, skills,
                Set.of(), false, days, hours, null, null, 0, null);
    }

    static Insights.Work chat(int skill, String source, String mode, int conversations, String note, boolean approved) {
        return new Insights.Work(UUID.randomUUID(), "CHAT_EXPORT", "chat", 0, 0, false, false, null, NOW,
                approved ? Set.of(skill) : Set.of(), approved ? Set.of() : Set.of(skill), false, null, null, source, mode,
                conversations, note);
    }

    static int[] spread(int total, int slots, int... peaks) {
        int[] a = new int[slots];
        for (int i = 0; i < total; i++) a[peaks.length > 0 && i % 10 < 6 ? peaks[i % peaks.length] : i % slots]++;
        return a;
    }

    static Instant daysAgo(long d) {
        return NOW.minus(Duration.ofDays(d));
    }

    /** A data-leaning candidate: SQL, pandas and Power BI dominate, with some Python and React. */
    static List<Insights.Skill> dataSkills() {
        return List.of(skill(17, "Data", 84, "VERIFIED", 5), skill(20, "Data", 78, "VERIFIED", 10),
                skill(30, "Data", 60, "SHOWN", null), skill(1, "Languages", 88, "VERIFIED", 10), skill(60, "Web", 40, "SHOWN", null));
    }

    @Test
    void archetypeFollowsTheDomainWithMostProvenSkillAndExplainsItself() {
        Read.Archetype a = Insights.archetype(dataSkills(), List.of(), null);
        assertEquals("DATA", a.key());
        assertEquals("The Data Storyteller", a.name());
        // 222 of 262 non-language points are data: 85%. Languages (Python) don't count towards any archetype.
        assertTrue(a.reason().startsWith("85% of your proven skill is in data and databases: SQL, pandas and Power BI"), a.reason());
        assertEquals(List.of("SQL", "pandas", "Power BI"), a.skills());
    }

    @Test
    void anEvenSpreadIsAPolymath() {
        List<Insights.Skill> spread = List.of(skill(17, "Data", 70, "VERIFIED", null), skill(42, "AI & ML", 68, "VERIFIED", null),
                skill(60, "Web", 66, "VERIFIED", null), skill(80, "DevOps & Cloud", 64, "SHOWN", null));
        Read.Archetype a = Insights.archetype(spread, List.of(), null);
        assertEquals("POLYMATH", a.key());
        assertTrue(a.reason().contains("no area above"), a.reason());
        assertEquals(3, a.skills().size());
    }

    @Test
    void edgeCaseLittleOrNoEvidenceIsAnExplorer() {
        assertEquals("EXPLORER", Insights.archetype(List.of(), List.of(), null).key());
        assertEquals("No skills are backed by work yet.", Insights.archetype(List.of(), List.of(), null).reason());
        // Claimed skills don't count, however many.
        List<Insights.Skill> claimed = List.of(skill(17, "Data", 0, "CLAIMED", null), skill(20, "Data", 0, "CLAIMED", null),
                skill(30, "Data", 0, "CLAIMED", null), skill(42, "AI & ML", 30, "SHOWN", null));
        Read.Archetype a = Insights.archetype(claimed, List.of(), null);
        assertEquals("EXPLORER", a.key());
        assertEquals("1 skill is backed by work so far.", a.reason());
        // Only languages: proven, but no area to call it.
        assertEquals("EXPLORER", Insights.archetype(List.of(skill(1, "Languages", 90, "VERIFIED", null),
                skill(1, "Languages", 90, "VERIFIED", null), skill(1, "Languages", 90, "VERIFIED", null)), List.of(), null).key());
    }

    @Test
    void traitsComeFromWhenCommitsHappenAndNeedEnoughCommits() {
        int[] lateHours = new int[24];
        lateHours[23] = 12;
        lateHours[1] = 6;
        lateHours[15] = 12;
        int[] days = {5, 5, 5, 5, 4, 3, 3};
        Read.Rhythm night = new Read.Rhythm(days, lateHours, 30);
        Read.Trait t = Insights.trait(List.of(), night);
        assertEquals("NIGHT_OWL", t.key());
        assertEquals("60% of your commits land between 10 pm and 5 am.", t.line());

        int[] weekend = {2, 2, 2, 2, 2, 10, 10};
        int[] flat = spread(30, 24);
        assertEquals("WEEKEND_BUILDER", Insights.trait(List.of(), new Read.Rhythm(weekend, flat, 30)).key());

        // Edge: the same shape with only 10 commits says nothing.
        int[] fewHours = new int[24];
        fewHours[23] = 10;
        assertNull(Insights.trait(List.of(), new Read.Rhythm(new int[]{10, 0, 0, 0, 0, 0, 0}, fewHours, 10)));

        // Steady: commits in most weeks since the first repository.
        List<Insights.Work> steady = List.of(repo("a", 30, 12, daysAgo(120), daysAgo(20), Set.of(17), null, null));
        assertEquals("STEADY", Insights.trait(steady, null).key());
        // Finisher: three repositories each worked on for a month or more.
        List<Insights.Work> finished = List.of(repo("a", 10, 5, daysAgo(900), daysAgo(860), Set.of(), null, null),
                repo("b", 10, 4, daysAgo(700), daysAgo(660), Set.of(), null, null),
                repo("c", 10, 6, daysAgo(300), daysAgo(250), Set.of(), null, null));
        assertEquals("FINISHER", Insights.trait(finished, null).key());
    }

    @Test
    void constellationLinksSkillsUsedTogetherAndIgnoresTalkAndCopies() {
        List<Insights.Work> work = List.of(
                repo("dash", 40, 8, daysAgo(200), daysAgo(10), Set.of(17, 20, 30), null, null),
                repo("etl", 20, 4, daysAgo(400), daysAgo(300), Set.of(17, 20), null, null),
                new Insights.Work(UUID.randomUUID(), "GITHUB_REPO", "copy", 50, 9, false, true, null, NOW, Set.of(17, 60),
                        Set.of(), false, null, null, null, null, 0, null),
                chat(60, "EXPORT", "ASKED", 5, null, true));
        Read.Constellation c = Insights.constellation(dataSkills(), work);
        assertEquals(5, c.stars().size());
        assertEquals(new Read.Link(17, 20, 2), c.links().get(0));
        assertTrue(c.links().contains(new Read.Link(17, 30, 1)));
        assertTrue(c.links().contains(new Read.Link(20, 30, 1)));
        // The duplicate and the chat link nothing to React.
        assertTrue(c.links().stream().noneMatch(l -> l.a() == 60 || l.b() == 60), c.links().toString());
        assertEquals(3, c.links().size());
    }

    @Test
    void saidVersusDoneSortsEdgesUnprovenStrengthsAndInSync() {
        List<Insights.Work> work = List.of(
                repo("dash", 40, 8, daysAgo(200), daysAgo(10), Set.of(17, 20), null, null),
                repo("etl", 25, 5, daysAgo(400), daysAgo(300), Set.of(20, 90), null, null),
                chat(80, "EXPORT", "ASKED", 6, null, false),                    // Docker: talked about, never built
                chat(42, "MEMORY", "LEARNING", 0, "working through a course", false),
                chat(60, "MEMORY", "USED", 0, null, false),                      // React: "used", nothing public
                chat(17, "EXPORT", "ASKED", 3, null, true));                     // SQL: both
        Read.SaidDone sd = Insights.saidDone(dataSkills(), work, NAMES);
        assertNotNull(sd);
        assertTrue(sd.hasHistory());
        assertEquals(List.of("Docker", "scikit-learn"), sd.edges().stream().map(Read.Pair::name).toList());
        assertEquals("Came up in 6 of your AI conversations.", sd.edges().get(0).said());
        assertEquals("Your AI remembers you learning it. Working through a course.", sd.edges().get(1).said());
        assertEquals(List.of("React"), sd.unproven().stream().map(Read.Pair::name).toList());
        assertEquals("Nothing public shows it yet.", sd.unproven().get(0).done());
        assertEquals(List.of("SQL"), sd.inSync().stream().map(Read.Pair::name).toList());
        assertEquals("1 repository, 40 commits.", sd.inSync().get(0).done());
        // pandas: two repositories, never talked about. Algorithms: one repo of 25 commits counts too.
        assertEquals(List.of("pandas", "Algorithms"), sd.strengths().stream().map(Read.Pair::name).toList());
        assertEquals("2 repositories, 65 commits.", sd.strengths().get(0).done());
    }

    @Test
    void edgeCaseNoHistoryStillShowsStrengthsAndNothingAtAllIsNull() {
        List<Insights.Work> work = List.of(repo("dash", 40, 8, daysAgo(200), daysAgo(10), Set.of(17), null, null));
        Read.SaidDone sd = Insights.saidDone(dataSkills(), work, NAMES);
        assertFalse(sd.hasHistory());
        assertEquals(List.of("SQL"), sd.strengths().stream().map(Read.Pair::name).toList());
        assertNull(Insights.saidDone(List.of(), List.of(), NAMES));
    }

    @Test
    void insightsAreRankedCappedAndOnlyClaimWhatTheDataShows() {
        int[] days = {3, 4, 14, 4, 3, 1, 1};          // Wednesday
        int[] hours = new int[24];
        hours[22] = 12;
        hours[14] = 18;
        List<Insights.Work> work = new ArrayList<>(List.of(
                repo("sales-dashboard", 62, 14, daysAgo(200), daysAgo(9), Set.of(17, 20), days, hours),
                repo("etl", 10, 3, daysAgo(100), daysAgo(80), Set.of(17), null, null),
                repo("old", 5, 2, daysAgo(500), daysAgo(480), Set.of(), null, null)));
        Read read = Insights.read(dataSkills(), work, NAMES, NOW);
        List<String> keys = read.insights().stream().map(Read.Insight::key).toList();
        assertEquals(4, keys.size());
        assertEquals("TOP_SKILL", keys.get(0));
        assertEquals("Top 5% in SQL", read.insights().get(0).title());
        assertTrue(keys.contains("DEEPEST"), keys.toString());
        assertTrue(read.insights().stream().anyMatch(i -> i.title().equals("Your deepest work is sales-dashboard")));
        // 2 repositories started in the last year, 1 the year before.
        assertTrue(keys.contains("GROWTH"), keys.toString());
        assertFalse(keys.contains("QUIET"), "the last commit was 9 days ago");
        assertEquals(30, read.rhythm().commits());
        assertEquals(3, read.stats().repositories());
        assertEquals(77, read.stats().commits());
        assertEquals(daysAgo(500), read.stats().since());
    }

    @Test
    void aLongQuietSpellIsFlaggedWhileItIsCheapToFix() {
        List<Insights.Work> work = List.of(repo("dash", 40, 8, daysAgo(400), daysAgo(130), Set.of(17), null, null));
        Read read = Insights.read(List.of(skill(17, "Data", 50, "VERIFIED", null)), work, NAMES, NOW);
        assertTrue(read.insights().stream().anyMatch(i -> i.key().equals("QUIET")
                && i.title().equals("It's been 4 months since your last commit")), read.insights().toString());
    }

    @Test
    void thePublicReadLeavesOutAiHistoryAndPrivateInsights() {
        List<Insights.Work> work = List.of(repo("dash", 40, 8, daysAgo(200), daysAgo(10), Set.of(17, 20), null, null),
                chat(80, "EXPORT", "ASKED", 6, null, false));
        Read open = Insights.read(dataSkills(), work, NAMES, NOW).publicView();
        assertNull(open.saidDone());
        assertTrue(open.insights().isEmpty());
        assertEquals("DATA", open.archetype().key());
        assertEquals(1, open.constellation().links().size());
    }

    @Test
    void sameEvidenceSameRead() {
        List<Insights.Work> work = List.of(repo("dash", 40, 8, daysAgo(200), daysAgo(10), Set.of(17, 20, 30), null, null));
        assertEquals(Insights.read(dataSkills(), work, NAMES, NOW).insights(), Insights.read(dataSkills(), work, NAMES, NOW).insights());
        assertThrows(NullPointerException.class, () -> Insights.read(dataSkills(), work, NAMES, null));
    }
}
