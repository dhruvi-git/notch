package dev.notch.api.explain.core;

import dev.notch.explain.CodeMap;
import dev.notch.explain.CodeMapBuilder;
import dev.notch.explain.SourceFile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeDecisionLinksTest {

    private static final Instant OCT = Instant.parse("2026-10-05T10:00:00Z");
    private static final CodeDecisionLinks.Decision PG = new CodeDecisionLinks.Decision("d1",
            "Let's go with Postgres instead of MongoDB because we need joins.", List.of("MongoDB"), OCT);

    private final CodeMap map = new CodeMapBuilder().build(List.of(
            new SourceFile("src/db.ts", "import { Pool } from 'pg';\nexport const pool = new Pool();"),
            new SourceFile("src/events.ts", "import mongoose from 'mongoose';\nexport const events = 1;"),
            new SourceFile("src/cache.ts", "import Redis from 'ioredis';\nexport const cache = 1;")));

    @Test
    void filesFollowOrGoAgainstDecisions() {
        List<CodeDecisionLinks.Link> db = CodeDecisionLinks.forFile(map.file("src/db.ts"), List.of(PG));
        assertEquals(1, db.size());
        assertEquals(CodeDecisionLinks.Kind.FOLLOWS, db.get(0).kind());
        assertEquals("PostgreSQL", db.get(0).tool());
        List<CodeDecisionLinks.Link> events = CodeDecisionLinks.forFile(map.file("src/events.ts"), List.of(PG));
        assertEquals(CodeDecisionLinks.Kind.GOES_AGAINST, events.get(0).kind());
        assertTrue(CodeDecisionLinks.forFile(map.file("src/cache.ts"), List.of(PG)).isEmpty());
    }

    @Test
    void conflictsListCodeUsingRuledOutTools() {
        List<CodeDecisionLinks.Conflict> c = CodeDecisionLinks.conflicts(map, List.of(PG));
        assertEquals(1, c.size());
        assertEquals("src/events.ts", c.get(0).path());
        assertEquals("MongoDB", c.get(0).tool());
    }

    @Test
    void aLaterChoiceLiftsTheConflict() {
        CodeDecisionLinks.Decision mongoLater = new CodeDecisionLinks.Decision("d2", "We'll use MongoDB for raw events.", List.of(),
                OCT.plusSeconds(86_400));
        assertTrue(CodeDecisionLinks.conflicts(map, List.of(PG, mongoLater)).isEmpty());
    }

    @Test
    void toolUsageShowsWhatEachToolIsCoveredBy() {
        List<CodeDecisionLinks.ToolUse> uses = CodeDecisionLinks.toolUsage(map, List.of(PG));
        assertEquals(3, uses.size());
        CodeDecisionLinks.ToolUse redis = uses.stream().filter(u -> u.tool().equals("Redis")).findFirst().orElseThrow();
        assertNull(redis.decisionId(), "no decision covers Redis yet");
        CodeDecisionLinks.ToolUse mongo = uses.stream().filter(u -> u.tool().equals("MongoDB")).findFirst().orElseThrow();
        assertEquals(CodeDecisionLinks.Kind.GOES_AGAINST, mongo.kind());
    }

    @Test
    void limitsRejectBrokenOrHugeMaps() {  // invalid input
        assertNull(MapLimits.problem(map));
        assertNotNull(MapLimits.problem(null));
        CodeMap empty = new CodeMapBuilder().build(List.of());
        assertTrue(MapLimits.problem(empty).startsWith("The map has no files"));
        CodeMap.FileInfo f = map.files().get(0);
        CodeMap.FileInfo longSummary = new CodeMap.FileInfo(f.path(), f.language(), 1, f.role(), null, "x".repeat(401),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "src", 0);
        CodeMap bad = new CodeMap(map.stats(), List.of(longSummary), List.of(), List.of(), List.of(), List.of(), List.of());
        assertEquals("Summaries must be under 400 characters.", MapLimits.problem(bad));
    }
}
