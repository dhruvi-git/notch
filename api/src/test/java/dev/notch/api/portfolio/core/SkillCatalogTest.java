package dev.notch.api.portfolio.core;

import dev.notch.api.portfolio.core.SkillCatalog.Skill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SkillCatalogTest {

    public static final SkillCatalog CATALOG = SkillCatalog.of(List.of(
            new Skill(1, "Python", "languages"),
            new Skill(2, "SQL", "data"),
            new Skill(3, "Machine Learning", "ml"),
            new Skill(4, "pandas", "data"),
            new Skill(5, "C++", "languages"),
            new Skill(6, "Node.js", "web"),
            new Skill(7, "PostgreSQL", "data"),
            new Skill(8, "Power BI", "data")
    ), Map.of("ml", 3, "postgres", 7, "pandas df", 4, "nodejs", 6, "py", 1));

    @Test
    void resolvesNamesAndAliasesIgnoringCaseAndPunctuation() {
        assertEquals(4, CATALOG.resolve("  Pandas DF ").orElseThrow().id());
        assertEquals(7, CATALOG.resolve("Postgres!").orElseThrow().id());
        assertEquals(5, CATALOG.resolve("c++").orElseThrow().id());
        assertEquals(6, CATALOG.resolve("NODE.JS").orElseThrow().id());
        assertTrue(CATALOG.resolve("cobol").isEmpty());
    }

    @Test
    void extractsLongestMatchInOrderOfMention() {
        List<Skill> found = CATALOG.extract("Built a machine learning pipeline in Python with pandas, "
                + "stored results in Postgres and charted them in Power BI. Also some C++.");
        assertEquals(List.of(3, 1, 4, 7, 8, 5), found.stream().map(Skill::id).toList());
    }

    @Test
    void edgeCaseEmptyOrPunctuationOnlyText() {
        assertEquals(List.of(), CATALOG.extract(""));
        assertEquals(List.of(), CATALOG.extract("... !!! ---"));
        assertEquals(List.of(), CATALOG.extract(null));
    }

    @Test
    void autocompleteRanksNamesAboveAliasesAndDeduplicates() {
        List<Skill> p = CATALOG.autocomplete("p", 10);
        assertEquals(1, p.stream().filter(s -> s.id() == 4).count()); // pandas and "pandas df" collapse
        assertTrue(p.stream().map(Skill::id).toList().containsAll(List.of(1, 4, 7, 8)));
        assertEquals(List.of(7), CATALOG.autocomplete("postg", 5).stream().map(Skill::id).toList());
        assertEquals(List.of(), CATALOG.autocomplete("zz", 5));
    }

    @Test
    void rejectsConflictingCatalogues() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillCatalog.of(List.of(new Skill(1, "R", "x"), new Skill(1, "Go", "x")), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SkillCatalog.of(List.of(new Skill(1, "SQL", "x"), new Skill(2, "sql", "x")), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SkillCatalog.of(List.of(new Skill(1, "SQL", "x")), Map.of("mysql", 9)));
        assertThrows(IllegalArgumentException.class, () -> CATALOG.autocomplete("p", 0));
    }
}
