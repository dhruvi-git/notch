package dev.notch.api.ownership.core;

import dev.notch.api.ownership.core.QuestionPlanner.Plan;
import dev.notch.api.ownership.core.QuestionPlanner.Planned;
import dev.notch.api.ownership.core.RegionExtractor.Region;
import dev.notch.api.ownership.core.RegionExtractor.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QuestionPlannerTest {

    private static final List<Region> REGIONS = RegionExtractor.extract(SampleRepo.FILES, SampleRepo.CATALOG);
    private static final Set<Integer> FOCUS = Set.of(1, 4, 2, 17, 20, 42); // Python, TypeScript, Java, SQL, pandas, scikit-learn

    @Test
    void coversEverySkillAndConceptWithinTheBudget() {
        Plan p = QuestionPlanner.plan(REGIONS, FOCUS, SampleRepo.CATALOG, 8);
        assertEquals(p.elements(), p.covered(), "uncovered: " + p.uncovered());
        assertTrue(p.questions().size() <= 8);
        Set<String> skills = new HashSet<>();
        p.questions().forEach(q -> skills.addAll(q.skillNames()));
        assertTrue(skills.containsAll(Set.of("Python", "TypeScript", "Java", "SQL", "pandas", "scikit-learn")), skills.toString());
    }

    @Test
    void aSmallBudgetReportsWhatIsLeftOut() {
        Plan p = QuestionPlanner.plan(REGIONS, FOCUS, SampleRepo.CATALOG, 2);
        assertEquals(2, p.questions().size());
        assertTrue(p.covered() < p.elements());
        assertEquals(p.elements() - p.covered(), p.uncovered().size());
        // Each question says what it was picked to cover, and together they add up to the coverage.
        assertEquals(p.covered(), p.questions().stream().mapToInt(q -> q.covers().size()).sum());
    }

    @Test
    void questionsNameTheCodeAndAskEachConceptOnce() {
        Plan p = QuestionPlanner.plan(REGIONS, FOCUS, SampleRepo.CATALOG, 5);
        Set<Concept> asked = new HashSet<>();
        for (Planned q : p.questions()) {
            assertTrue(q.prompt().contains(q.region().name()) || q.prompt().contains(q.region().path()), q.prompt());
            assertFalse(q.prompt().contains("{"), "template filled: " + q.prompt());
            if (q.concept() != null) assertTrue(asked.add(q.concept()), "asked twice: " + q.concept());
            assertFalse(q.rubric().isEmpty());
        }
    }

    @Test
    void edgeCaseTinyRepositoryStillGetsSeveralQuestions() {
        String py = """
                def mean(values):
                    total = 0
                    for v in values:
                        total += v
                    return total / len(values)

                def median(values):
                    ordered = sorted(values)
                    middle = len(ordered) // 2
                    return ordered[middle]

                def spread(values):
                    low = min(values)
                    high = max(values)
                    return high - low
                """;
        List<Region> r = RegionExtractor.extract(List.of(new SourceFile("stats.py", py)), SampleRepo.CATALOG);
        Plan p = QuestionPlanner.plan(r, Set.of(1), SampleRepo.CATALOG, 5);
        assertEquals(3, p.questions().size());
    }

    @Test
    void edgeCaseNothingToAskAbout() {
        Plan p = QuestionPlanner.plan(List.of(), FOCUS, SampleRepo.CATALOG, 5);
        assertEquals(List.of(), p.questions());
        assertThrows(IllegalArgumentException.class, () -> QuestionPlanner.plan(REGIONS, FOCUS, SampleRepo.CATALOG, 0));
    }
}
