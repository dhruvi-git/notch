package dev.notch.api.review.core;

import dev.notch.api.explain.core.CodeDecisionLinks;
import dev.notch.api.review.ReviewReport;
import dev.notch.explain.CodeMap;
import dev.notch.explain.CodeMapBuilder;
import dev.notch.explain.SourceFile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReviewCoreTest {

    private static final CodeDecisionLinks.Decision PG = new CodeDecisionLinks.Decision("d1",
            "Let's go with Postgres instead of MongoDB because we need joins.", List.of("MongoDB"),
            Instant.parse("2026-10-05T10:00:00Z"));
    private static final String KEY = "sk_live_" + "51HxQzLkT9fRmP2vB8nYwC4dJ6gK0sA3eU7iO5hX1";

    private static final List<SourceFile> FILES = List.of(
            new SourceFile("src/index.ts", "import { pool } from './db';\nimport { events } from './events';\nexport const app = 1;\n"),
            new SourceFile("src/db.ts", "import { Pool } from 'pg';\n// TODO pool size\nexport const pool = new Pool();\n"),
            new SourceFile("src/events.ts", "import mongoose from 'mongoose';\n// TODO\n// FIXME\nexport const events = 1;\n"),
            new SourceFile("src/pay.ts", "import { pool } from './db';\nconst stripe = '" + KEY + "';\nexport const pay = 1;\n"),
            new SourceFile("src/old.ts", "export const unused = 1;\n"),
            new SourceFile(".env", "PORT=3000\n"));

    private static Map<String, String> contents() {
        Map<String, String> m = new HashMap<>();
        FILES.forEach(f -> m.put(f.path(), f.content()));
        return m;
    }

    private final CodeMap map = new CodeMapBuilder().build(FILES);

    @Test
    void theChecksFindDecisionConflictsSecretsAndGaps() {
        List<ReviewReport.Issue> found = ReviewRules.findings(map, contents(), List.of(PG));
        ReviewReport.Issue decision = found.stream().filter(i -> "decision".equals(i.category())).findFirst().orElseThrow();
        assertEquals("src/events.ts", decision.file());
        assertEquals("high", decision.severity());
        assertTrue(decision.detail().contains("Postgres instead of MongoDB"));

        ReviewReport.Issue secret = found.stream().filter(i -> "security".equals(i.category()) && "src/pay.ts".equals(i.file()))
                .findFirst().orElseThrow();
        assertEquals(2, secret.line(), "the secret's line is named");
        assertFalse(secret.detail().contains(KEY), "the secret itself is never repeated");

        assertTrue(found.stream().anyMatch(i -> ".env".equals(i.file())), "a committed .env is flagged");
        assertTrue(found.stream().anyMatch(i -> "testing".equals(i.category())), "no tests at all is flagged");
        assertTrue(found.stream().anyMatch(i -> i.title().contains("TODO")), "leftover TODOs are counted");
        assertTrue(found.stream().allMatch(i -> "rules".equals(i.source())));
    }

    @Test
    void withoutTheCodeOnlyTheMapChecksRun() {
        List<ReviewReport.Issue> found = ReviewRules.findings(map, Map.of(), List.of(PG));
        assertTrue(found.stream().anyMatch(i -> "decision".equals(i.category())));
        assertTrue(found.stream().noneMatch(i -> i.title().contains("TODO")));
    }

    @Test
    void anOfflineReportStandsOnItsOwn() {
        List<ReviewReport.Issue> found = ReviewRules.findings(map, contents(), List.of(PG));
        ReviewReport r = ReviewRules.offlineReport("billing", map, found, 1,
                new ReviewReport.Coverage("automated checks", 5, 5, 20, "No AI model is set up."));
        assertTrue(r.summary().startsWith("billing has"));
        assertFalse(r.areas().isEmpty());
        assertEquals("now", r.recommendations().get(0).priority());
    }

    @Test
    void selectionPutsCentralCodeFirstRedactsSecretsAndNumbersLines() {
        ReviewSelection.Selection sel = ReviewSelection.select(map, contents(), ReviewSelection.DEFAULT_BUDGET_CHARS);
        assertEquals("src/db.ts", sel.files().get(0).path(), "the most depended-on file comes first");
        ReviewSelection.Picked pay = sel.files().stream().filter(p -> p.path().equals("src/pay.ts")).findFirst().orElseThrow();
        assertFalse(pay.numbered().contains(KEY), "secrets never reach the model");
        assertTrue(pay.numbered().startsWith("1| import { pool }"));
        assertTrue(sel.files().stream().noneMatch(p -> p.path().equals(".env")));

        ReviewSelection.Selection tiny = ReviewSelection.select(map, contents(), 200);
        assertTrue(tiny.files().size() < sel.files().size(), "the budget is respected: " + tiny.files().stream().map(ReviewSelection.Picked::path).toList() + " vs " + sel.files().stream().map(ReviewSelection.Picked::path).toList());
    }

    @Test
    void untrustedReviewsAreCleanedAndInventedFilesDropped() {
        Map<String, Object> raw = Map.of(
                "summary", "Fine overall.",
                "areas", List.of(Map.of("name", "API", "rating", "Needs work", "assessment", "Thin.")),
                "issues", List.of(
                        Map.of("severity", "Critical", "category", "Correctness", "title", "Negative refunds", "file", "./src/pay.ts:2",
                                "detail", "x"),
                        Map.of("severity", "low", "category", "bug", "title", "Beyond the end", "file", "src/db.ts", "line", 999),
                        Map.of("severity", "high", "category", "bug", "title", "In a file never shown", "file", "src/ghost.ts")),
                "recommendations", List.of(Map.of("priority", "low", "title", "Later"), Map.of("priority", "urgent", "title", "First")));
        ReviewReport r = ReviewSanitizer.clean(raw, Map.of("src/pay.ts", 3, "src/db.ts", 3), "model", null);
        assertEquals(2, r.issues().size(), "the issue citing a file the model never saw is dropped");
        ReviewReport.Issue first = r.issues().get(0);
        assertEquals("high", first.severity());
        assertEquals("bug", first.category());
        assertEquals("src/pay.ts", first.file());
        assertEquals(2, first.line(), "a path:line citation is split");
        assertNull(r.issues().get(1).line(), "a line past the end of the file is removed");
        assertEquals("weak", r.areas().get(0).rating());
        assertEquals(List.of("First", "Later"), r.recommendations().stream().map(ReviewReport.Recommendation::title).toList());
    }

    @Test
    void mergingKeepsTheChecksCertainFindingsAndTheReviewersOwn() {
        List<ReviewReport.Issue> rules = ReviewRules.findings(map, contents(), List.of(PG));
        ReviewReport fromModel = ReviewSanitizer.clean(Map.of(
                "summary", "Model summary.",
                "issues", List.of(
                        Map.of("severity", "low", "category", "decision", "title", "Mongo here", "file", "src/events.ts"),
                        Map.of("severity", "medium", "category", "bug", "title", "Pool never closed", "file", "src/db.ts", "line", 3),
                        Map.of("severity", "medium", "category", "bug", "title", "Second bug, same file", "file", "src/db.ts"),
                        Map.of("severity", "medium", "category", "testing", "title", "Payments untested"))), null, "model", null);
        ReviewReport merged = ReviewSanitizer.merge(fromModel, rules, fromModel);
        List<ReviewReport.Issue> decisions = merged.issues().stream().filter(i -> "decision".equals(i.category())).toList();
        assertEquals(1, decisions.size());
        assertEquals("rules", decisions.get(0).source(), "the certain rule finding wins over the model's guess");
        assertEquals(2, merged.issues().stream().filter(i -> "src/db.ts".equals(i.file()) && "bug".equals(i.category())).count(),
                "two bugs in one file both stay");
        assertEquals(1, merged.issues().stream().filter(i -> "testing".equals(i.category())).count(),
                "the reviewer's testing finding replaces the general rule");
        assertEquals("high", merged.issues().get(0).severity());
        assertEquals("Model summary.", merged.summary());
    }
}
