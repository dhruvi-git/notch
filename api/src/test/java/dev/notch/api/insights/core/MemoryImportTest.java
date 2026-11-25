package dev.notch.api.insights.core;

import dev.notch.api.portfolio.core.SkillCatalog;
import dev.notch.api.portfolio.core.SkillCatalog.Skill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryImportTest {

    static final SkillCatalog CATALOG = SkillCatalog.of(List.of(
            new Skill(1, "Python", "Languages"), new Skill(5, "C#", "Languages"), new Skill(17, "SQL", "Data"),
            new Skill(20, "pandas", "Data"), new Skill(30, "Power BI", "Data"), new Skill(42, "scikit-learn", "AI & ML"),
            new Skill(60, "React", "Web"), new Skill(80, "Docker", "DevOps & Cloud"), new Skill(81, "PostgreSQL", "Databases")),
            Map.of("sklearn", 42, "postgres", 81, "powerbi", 30));

    static final String CLEAN = """
            NOTCH PROFILE
            SKILLS
            - Python | USED | Wrote ETL scripts for your college fest
            - Docker | LEARNING | Working through a containers course
            - React | ASKED | Asked how hooks work
            PROJECTS
            - Fest ticket dashboard | Live ticket sales for the organising team, built with Power BI | SQL, Power BI
            GOAL
            - Data analyst at a product company
            END""";

    @Test
    void readsTheTemplateAsWritten() {
        MemoryImport.Parsed p = MemoryImport.parse(CLEAN, CATALOG);
        assertEquals(List.of("Python", "Docker", "React"), p.skills().stream().map(s -> s.skill().name()).toList());
        assertEquals(List.of(MemoryImport.Mode.USED, MemoryImport.Mode.LEARNING, MemoryImport.Mode.ASKED),
                p.skills().stream().map(MemoryImport.SkillLine::mode).toList());
        assertEquals("Wrote ETL scripts for your college fest", p.skills().get(0).note());
        assertEquals(1, p.projects().size());
        MemoryImport.ProjectLine proj = p.projects().get(0);
        assertEquals("Fest ticket dashboard", proj.title());
        assertEquals(List.of("SQL", "Power BI"), proj.skills().stream().map(Skill::name).toList());
        assertEquals("Data analyst at a product company", p.goal());
        assertTrue(p.unrecognised().isEmpty());
    }

    @Test
    void survivesWhatAssistantsActuallySend() {
        String messy = """
                Sure! Here's what I remember:

                ```
                **NOTCH PROFILE**
                ### Skills:
                * **sklearn** | used | trained a churn model
                • C# | Learning
                1. postgres | used | your capstone database
                2. Kubernetes | asked | wanted to know about pods
                ## Projects
                - **Churn predictor** | Predicts which customers leave | Python, scikit-learn
                ## Goal:
                - ML engineer
                END
                ```
                Let me know if you'd like changes!""";
        MemoryImport.Parsed p = MemoryImport.parse(messy, CATALOG);
        assertEquals(List.of("scikit-learn", "C#", "PostgreSQL"), p.skills().stream().map(s -> s.skill().name()).toList());
        assertEquals(MemoryImport.Mode.LEARNING, p.skills().get(1).mode());
        assertNull(p.skills().get(1).note());
        assertEquals(List.of("Kubernetes"), p.unrecognised());
        assertEquals("Churn predictor", p.projects().get(0).title());
        assertEquals("ML engineer", p.goal());
    }

    @Test
    void theSameSkillTwiceKeepsTheStrongestRelationship() {
        String twice = """
                NOTCH PROFILE
                SKILLS
                - SQL | ASKED | joins
                - SQL | USED | built the fest database
                - pandas | LEARNING
                - pandas | ASKED
                END""";
        MemoryImport.Parsed p = MemoryImport.parse(twice, CATALOG);
        assertEquals(2, p.skills().size());
        assertEquals(MemoryImport.Mode.USED, p.skills().get(0).mode());
        assertEquals("built the fest database", p.skills().get(0).note());
        assertEquals(MemoryImport.Mode.LEARNING, p.skills().get(1).mode());
    }

    @Test
    void edgeCaseEmptySectionsAndNothingRemembered() {
        String empty = """
                NOTCH PROFILE
                SKILLS
                - None
                PROJECTS
                GOAL
                - n/a
                END""";
        MemoryImport.Parsed p = MemoryImport.parse(empty, CATALOG);
        assertTrue(p.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MemoryImport.parse("   ", CATALOG));
        assertThrows(IllegalArgumentException.class, () -> MemoryImport.parse(null, CATALOG));
    }

    @Test
    void edgeCaseTextThatIsNotAReplyIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MemoryImport.parse("I don't have memory of past conversations, sorry.", CATALOG));
        assertTrue(e.getMessage().startsWith("This doesn't look like a reply"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> MemoryImport.parse("x".repeat(MemoryImport.MAX_TEXT + 1), CATALOG));
    }

    @Test
    void stopsAtEndCapsProjectsAndTrimsLongNotes() {
        StringBuilder b = new StringBuilder("NOTCH PROFILE\nSKILLS\n- SQL | USED | ").append("a".repeat(400)).append("\nPROJECTS\n");
        for (int i = 0; i < 15; i++) b.append("- Project ").append(i).append(" | something | SQL\n");
        b.append("- Project 3 | a repeat | SQL\nEND\nSKILLS\n- Docker | USED\n");
        MemoryImport.Parsed p = MemoryImport.parse(b.toString(), CATALOG);
        assertEquals(1, p.skills().size(), "nothing after END is read");
        assertEquals(200, p.skills().get(0).note().length());
        assertEquals(MemoryImport.MAX_PROJECTS, p.projects().size());
        assertTrue(p.projects().stream().map(MemoryImport.ProjectLine::title).distinct().count() == p.projects().size());
    }

    @Test
    void thePromptAsksForTheFormatTheParserReads() {
        assertTrue(MemoryImport.PROMPT.contains("NOTCH PROFILE"));
        assertTrue(MemoryImport.PROMPT.contains("Don't guess or invent anything"));
        // The template inside the prompt, filled in, parses.
        String template = MemoryImport.PROMPT.substring(MemoryImport.PROMPT.indexOf("NOTCH PROFILE"))
                .replace("- skill name | USED or LEARNING or ASKED | what you remember, in one line", "- SQL | USED | dashboards");
        assertEquals("SQL", MemoryImport.parse(template, CATALOG).skills().get(0).skill().name());
    }

    /** Copied out of ChatGPT: Markdown escapes before every dash and a hard-break backslash ending every line. */
    @Test
    void readsAReplyCopiedWithMarkdownEscapes() {
        String copied = """
                NOTCH PROFILE\\
                SKILLS\\
                \\- Python | USED | Used for AI/ML projects, backend development, and machine learning experimentation.\\
                \\- Docker | ASKED | Worked through containers for a project.\\
                \\- SQL | USED | Worked with SQL databases and backend applications.\\
                PROJECTS\\
                \\- Fest ticket dashboard \\| Live ticket sales \\| SQL, Power BI\\
                GOAL\\
                \\- Data analyst\\
                END""";
        MemoryImport.Parsed p = MemoryImport.parse(copied, CATALOG);
        assertEquals(List.of("Python", "Docker", "SQL"), p.skills().stream().map(s -> s.skill().name()).toList());
        assertEquals(MemoryImport.Mode.USED, p.skills().get(0).mode());
        assertEquals("Used for AI/ML projects, backend development, and machine learning experimentation.", p.skills().get(0).note());
        assertEquals("Fest ticket dashboard", p.projects().get(0).title());
        assertEquals(List.of("SQL", "Power BI"), p.projects().get(0).skills().stream().map(Skill::name).toList());
        assertEquals("Data analyst", p.goal());
    }

    @Test
    void readsNonBreakingSpacesAndLookAlikePipes() {
        String copied = "NOTCH\u00A0PROFILE\nSKILLS\n- Python\u00A0｜ USED │ scripts\n- C\\# | LEARNING | games\nEND";
        MemoryImport.Parsed p = MemoryImport.parse(copied.replace("NOTCH\u00A0PROFILE", "NOTCH PROFILE"), CATALOG);
        assertEquals(List.of("Python", "C#"), p.skills().stream().map(s -> s.skill().name()).toList());
        assertEquals("scripts", p.skills().get(0).note());
        assertTrue(MemoryImport.normalize("NOTCH\u00A0PROFILE").equals("NOTCH PROFILE"));
        assertEquals("C:\\Users\\me", MemoryImport.normalize("C:\\Users\\me"), "a backslash before a letter stays");
    }
}
