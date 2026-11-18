package dev.notch.api.recruit.core;

import dev.notch.api.portfolio.core.SkillCatalogTest;
import dev.notch.api.recruit.core.JobParser.DraftRequirement;
import dev.notch.engine.score.SkillScoring.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobParserTest {

    private static final String JD = """
            Data Analyst Intern

            Requirements:
            - Strong SQL and Python skills
            - Comfortable building dashboards in Power BI
            - Machine learning experience is a plus
            """;

    private static DraftRequirement find(List<DraftRequirement> reqs, String name) {
        return reqs.stream().filter(r -> r.skill().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void classifiesEachSkillBySentence() {
        List<DraftRequirement> reqs = JobParser.parse(JD, SkillCatalogTest.CATALOG);
        assertEquals(4, reqs.size());
        DraftRequirement sql = find(reqs, "SQL");
        assertTrue(sql.mustHave());
        assertEquals(3, sql.weight());
        assertEquals(Level.VERIFIED, sql.minLevel());
        assertTrue(find(reqs, "Python").mustHave());
        assertEquals(2, find(reqs, "Power BI").weight());
        assertFalse(find(reqs, "Power BI").mustHave());
        assertEquals(1, find(reqs, "Machine Learning").weight());
        assertEquals(List.of(3.0, 3.0, 2.0, 1.0), reqs.stream().map(DraftRequirement::weight).toList());
    }

    @Test
    void strongestMentionWins() {
        List<DraftRequirement> reqs = JobParser.parse("Python is a plus. You must know Python.", SkillCatalogTest.CATALOG);
        assertEquals(1, reqs.size());
        assertTrue(reqs.get(0).mustHave());
    }

    @Test
    void edgeCaseNoKnownSkillsGivesEmptyDraft() {
        assertEquals(List.of(), JobParser.parse("We are a friendly team in Pune.", SkillCatalogTest.CATALOG));
    }

    @Test
    void rejectsEmptyDescription() {
        assertThrows(IllegalArgumentException.class, () -> JobParser.parse("   ", SkillCatalogTest.CATALOG));
        assertThrows(IllegalArgumentException.class, () -> JobParser.parse(null, SkillCatalogTest.CATALOG));
    }

    @Test
    void capsTheNumberOfRequirements() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("Python, SQL, pandas, C++, Node.js, PostgreSQL, Power BI, ML. ");
        assertTrue(JobParser.parse(sb.toString(), SkillCatalogTest.CATALOG).size() <= JobParser.MAX_REQUIREMENTS);
    }
}
