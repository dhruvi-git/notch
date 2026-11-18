package dev.notch.api.portfolio.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The seeded catalogue migration must load without two names claiming the same text. */
class SkillCatalogSeedTest {

    private static final Pattern SKILL = Pattern.compile("\\((\\d+), '([^']+)', '([^']+)'\\)");
    private static final Pattern ALIAS = Pattern.compile("\\('([^']+)', (\\d+)\\)");

    static SkillCatalog loadSeed() throws IOException {
        String sql;
        try (InputStream in = SkillCatalogSeedTest.class.getResourceAsStream("/db/migration/V11__skill_catalog.sql")) {
            assertNotNull(in, "seed migration on the classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int split = sql.indexOf("INSERT INTO skill_aliases");
        List<SkillCatalog.Skill> skills = new ArrayList<>();
        Matcher m = SKILL.matcher(sql.substring(0, split));
        while (m.find()) skills.add(new SkillCatalog.Skill(Integer.parseInt(m.group(1)), m.group(2), m.group(3)));
        Map<String, Integer> aliases = new HashMap<>();
        Matcher a = ALIAS.matcher(sql.substring(split));
        while (a.find()) assertNull(aliases.put(a.group(1), Integer.parseInt(a.group(2))), "alias twice: " + a.group(1));
        return SkillCatalog.of(skills, aliases);
    }

    @Test
    void seedLoadsAndResolvesCommonNames() throws IOException {
        SkillCatalog c = loadSeed();
        assertTrue(c.size() >= 90, "catalogue size " + c.size());
        assertEquals("PostgreSQL", c.resolve("postgres").orElseThrow().name());
        assertEquals("scikit-learn", c.resolve("sklearn").orElseThrow().name());
        assertEquals("Go (Golang)", c.resolve("golang").orElseThrow().name());
    }

    @Test
    void commonWordsAreNotReadAsSkills() throws IOException {
        SkillCatalog c = loadSeed();
        assertEquals(List.of(), c.extract("We go live with plan C after R&D signs off."));
    }
}
