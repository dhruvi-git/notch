package dev.notch.explain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeMapBuilderTest {

    private static SourceFile f(String path, String content) {
        return new SourceFile(path, content);
    }

    /** A small codebase with known answers. */
    private static final List<SourceFile> REPO = List.of(
            // Next.js app
            f("web/package.json", "{ \"scripts\": { \"mock\": \"node scripts/mock.mjs\" }, \"dependencies\": { \"pg\": \"8\" } }"),
            f("web/scripts/mock.mjs", "import http from 'node:http';\nhttp.createServer().listen(1);"),
            f("web/src/lib/db.ts", "/** Shared Postgres connection pool. */\nimport { Pool } from 'pg';\nexport const pool = new Pool();\nexport function query(sql: string) { return pool.query(sql); }"),
            f("web/src/lib/format.ts", "export function plural(n: number, w: string) { return n + ' ' + w; }"),
            f("web/src/components/Card.tsx", "import { plural } from '@/lib/format';\nexport function Card() { return null; }"),
            f("web/src/app/(app)/p/[projectId]/recall/page.tsx", "import { Card } from '@/components/Card';\nimport { query } from '../../../../../lib/db';\nexport default async function Recall() { return null; }"),
            f("web/src/app/api/n/[...path]/route.ts", "import { query } from '@/lib/db';\nasync function forward() {}\nexport { forward as GET, forward as POST };"),
            f("web/src/lib/old-helper.ts", "// import { query } from './db';\nexport const unusedThing = 1;"),
            f("web/src/lib/format.test.ts", "import { plural } from './format';\ntest('x', () => {});"),
            // Spring API
            f("api/src/main/java/dev/x/api/App.java", "package dev.x.api;\n@SpringBootApplication\npublic class App { public static void main(String[] a) {} }"),
            f("api/src/main/java/dev/x/api/billing/InvoiceController.java", "package dev.x.api.billing;\nimport dev.x.api.billing.core.Pricing;\n@RestController\n@RequestMapping(\"/api/invoices\")\npublic class InvoiceController {\n  private final InvoiceRepository repo;\n  @GetMapping(\"/{id}\") public Object get() { return null; }\n  @PostMapping public Object create() { return Pricing.total(); }\n}"),
            f("api/src/main/java/dev/x/api/billing/InvoiceRepository.java", "package dev.x.api.billing;\nimport org.postgresql.Driver;\n/** Reads and writes invoices. */\npublic class InvoiceRepository {}"),
            f("api/src/main/java/dev/x/api/billing/core/Pricing.java", "package dev.x.api.billing.core;\npublic final class Pricing { public static int total() { return 1; } }"),
            f("api/src/main/java/dev/x/api/billing/core/Legacy.java", "package dev.x.api.billing.core;\npublic class Legacy {}"),
            f("api/src/main/java/dev/x/api/billing/core/Tax.java", "package dev.x.api.billing.core;\npublic class Tax { int rate() { return Pricing.total(); } }"),
            // Python service
            f("svc/app/main.py", "\"\"\"Analytics service entry point.\"\"\"\nfrom fastapi import FastAPI\nfrom app.models import Event\nfrom . import store\napp = FastAPI()\n@app.post(\"/events\")\ndef add(): pass\n"),
            f("svc/app/models.py", "import pymongo\nclass Event: pass\n"),
            f("svc/app/store.py", "import os\ndef save(): pass\n"),
            // Go tool
            f("tool/go.mod", "module github.com/x/tool\n\ngo 1.22\n"),
            f("tool/main.go", "package main\n\nimport (\n  \"fmt\"\n  \"github.com/x/tool/internal/scan\"\n  \"github.com/redis/go-redis/v9\"\n)\n\nfunc main() { fmt.Println(scan.Run()) }"),
            f("tool/internal/scan/scan.go", "// Package scan walks a folder and counts files.\npackage scan\n\nfunc Run() int { return 0 }"),
            // Things to skip
            f("web/node_modules/pg/index.js", "module.exports = {}"),
            f("web/package-lock.json", "{}"),
            f("web/public/logo.png", "PNG"),
            f(".env.local", "OPENAI_API_KEY=sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEf"));

    private final CodeMap map = new CodeMapBuilder().build(REPO);

    private CodeMap.FileInfo file(String p) {
        CodeMap.FileInfo f = map.file(p);
        assertNotNull(f, p);
        return f;
    }

    @Test
    void skipsDependenciesLockfilesAndBinaries() {
        assertNull(map.file("web/node_modules/pg/index.js"));
        assertNull(map.file("web/package-lock.json"));
        assertNull(map.file("web/public/logo.png"));
        assertEquals(3, map.stats().skipped());
    }

    @Test
    void resolvesJsImportsIncludingAliasesAndRelativePaths() {
        assertEquals(List.of("web/src/components/Card.tsx", "web/src/lib/db.ts"),
                file("web/src/app/(app)/p/[projectId]/recall/page.tsx").imports());
        assertEquals(List.of("web/src/lib/format.ts"), file("web/src/components/Card.tsx").imports());
        assertTrue(file("web/src/lib/db.ts").importedBy().contains("web/src/app/api/n/[...path]/route.ts"));
        assertEquals(List.of("pg"), file("web/src/lib/db.ts").packages());
        assertEquals(List.of("PostgreSQL"), file("web/src/lib/db.ts").tools());
        assertTrue(file("web/scripts/mock.mjs").packages().isEmpty(), "node builtins aren't packages");
    }

    @Test
    void readsNextRoutesAndRoles() {
        CodeMap.FileInfo page = file("web/src/app/(app)/p/[projectId]/recall/page.tsx");
        assertEquals(Role.PAGE, page.role());
        assertEquals("/p/[projectId]/recall", page.route());
        CodeMap.FileInfo route = file("web/src/app/api/n/[...path]/route.ts");
        assertEquals(Role.API_ROUTE, route.role());
        assertEquals(List.of("GET /api/n/[...path]", "POST /api/n/[...path]"), route.endpoints());
        assertEquals(Role.COMPONENT, file("web/src/components/Card.tsx").role());
        assertEquals(Role.TEST, file("web/src/lib/format.test.ts").role());
        assertEquals(Role.CONFIG, file("web/package.json").role());
    }

    @Test
    void readsSpringControllersAndJavaImports() {
        CodeMap.FileInfo c = file("api/src/main/java/dev/x/api/billing/InvoiceController.java");
        assertEquals(Role.CONTROLLER, c.role());
        assertEquals(List.of("GET /api/invoices/{id}", "POST /api/invoices"), c.endpoints());
        // Pricing is imported; InvoiceRepository is used from the same package without an import.
        assertTrue(c.imports().contains("api/src/main/java/dev/x/api/billing/core/Pricing.java"));
        assertTrue(c.imports().contains("api/src/main/java/dev/x/api/billing/InvoiceRepository.java"));
        assertEquals(List.of("PostgreSQL"), file("api/src/main/java/dev/x/api/billing/InvoiceRepository.java").tools());
        assertEquals(Role.ENTRY, file("api/src/main/java/dev/x/api/App.java").role());
    }

    @Test
    void readsPythonAndGo() {
        CodeMap.FileInfo main = file("svc/app/main.py");
        assertEquals(List.of("svc/app/models.py", "svc/app/store.py"), main.imports());
        assertEquals(List.of("POST /events"), main.endpoints());
        assertEquals(List.of("fastapi"), main.packages());
        assertEquals(List.of("MongoDB"), file("svc/app/models.py").tools());
        CodeMap.FileInfo go = file("tool/main.go");
        assertEquals(List.of("tool/internal/scan/scan.go"), go.imports());
        assertEquals(List.of("github.com/redis/go-redis"), go.packages());
        assertEquals(Role.ENTRY, go.role());
    }

    @Test
    void summariesComeFromDocCommentsOrWhatTheFileIs() {
        assertEquals("Shared Postgres connection pool.", file("web/src/lib/db.ts").summary());
        assertEquals("Reads and writes invoices.", file("api/src/main/java/dev/x/api/billing/InvoiceRepository.java").summary());
        assertEquals("Analytics service entry point.", file("svc/app/main.py").summary());
        assertEquals("Walks a folder and counts files.", file("tool/internal/scan/scan.go").summary());
        assertEquals("Page at /p/[projectId]/recall.", file("web/src/app/(app)/p/[projectId]/recall/page.tsx").summary());
        assertEquals("API route at /api/n/[...path]: handles GET and POST.", file("web/src/app/api/n/[...path]/route.ts").summary());
        assertEquals("API controller with 2 endpoints, such as GET /api/invoices/{id}.",
                file("api/src/main/java/dev/x/api/billing/InvoiceController.java").summary());
    }

    @Test
    void onlyFileLevelDocCommentsBecomeTheSummary() {
        CodeMap m = new CodeMapBuilder().build(List.of(
                f("src/highlight.ts", "/** A run of text, marked or not. */\nexport type Segment = {};\nexport function segments() {}"),
                f("src/Search.java", "package x;\n/**\n * Hybrid search over one project.\n * <ul><li>Exact: suffix array</li></ul>\n */\n@Service\npublic class Search {}"),
                f("src/Handler.java", "package x;\n@RestControllerAdvice\npublic class Handler {}")));
        assertEquals("Defines Segment and segments.", m.file("src/highlight.ts").summary(), "the comment describes Segment, not the file");
        assertEquals("Hybrid search over one project.", m.file("src/Search.java").summary());
        assertEquals(Role.SERVICE, m.file("src/Handler.java").role(), "@RestControllerAdvice isn't a controller");
    }

    @Test
    void startHereIsTheMostDependedOnCode() {
        assertEquals("web/src/lib/db.ts", map.startHere().get(0), map.startHere().toString());
        assertTrue(map.startHere().size() <= CodeMapBuilder.START_HERE);
        assertFalse(map.startHere().contains("web/src/lib/format.test.ts"), "tests aren't where to start");
    }

    @Test
    void unusedFilesAreTheOnesNothingReaches() {
        // old-helper's only import is commented out; Legacy is in a package nobody imports from.
        assertTrue(map.possiblyUnused().contains("web/src/lib/old-helper.ts"), map.possiblyUnused().toString());
        assertTrue(map.possiblyUnused().contains("api/src/main/java/dev/x/api/billing/core/Legacy.java"));
        // A script that package.json runs, a same-package-only class and a test target are all reached.
        assertFalse(map.possiblyUnused().contains("web/scripts/mock.mjs"));
        assertFalse(map.possiblyUnused().contains("api/src/main/java/dev/x/api/billing/InvoiceRepository.java"));
        assertFalse(map.possiblyUnused().contains("web/src/lib/format.ts"));
        // Tax uses Pricing but nothing uses Tax.
        assertTrue(map.possiblyUnused().contains("api/src/main/java/dev/x/api/billing/core/Tax.java"));
    }

    @Test
    void impactFollowsImportersTransitively() {
        List<String> affected = map.affectedBy("web/src/lib/format.ts");
        assertEquals(List.of("web/src/components/Card.tsx", "web/src/lib/format.test.ts",
                "web/src/app/(app)/p/[projectId]/recall/page.tsx"), affected);
        assertTrue(map.affectedBy("no/such/file.ts").isEmpty());
    }

    @Test
    void areasAndModules() {
        assertTrue(map.areas().size() >= 3, map.areas().toString());  // web, api, svc, tool share no imports
        assertTrue(map.modules().stream().anyMatch(m -> m.path().equals("web/src/lib")));
        assertTrue(map.areas().get(0).files() >= map.areas().get(map.areas().size() - 1).files());
    }

    @Test
    void flagsSecretsWithoutKeepingThem() {
        assertEquals(List.of(".env.local"), map.stats().filesWithSecrets());
        assertEquals(1, file(".env.local").secrets());
    }

    @Test
    void linesAndFoldersAreCountedAsAPersonWould() {
        CodeMap m = new CodeMapBuilder().build(List.of(
                f("src/events/store.ts", "export const a = 1;\nexport const b = 2;\n"),
                f("src/events/track.ts", "import { a } from './store';"),
                f("src/components/Table.tsx", "export function Table() { return null; }")));
        assertEquals(2, m.file("src/events/store.ts").lines(), "the trailing newline isn't a third line");
        assertEquals(1, m.file("src/events/track.ts").lines());
        assertEquals(List.of("src/components", "src/events"), m.modules().stream().map(CodeMap.Module::path).toList(),
                "small folders keep their own name instead of becoming \"src\"");
    }

    @Test
    void emptyAndOversizedInputsAreSafe() {  // boundary
        CodeMap empty = new CodeMapBuilder().build(List.of());
        assertEquals(0, empty.stats().files());
        assertTrue(empty.startHere().isEmpty());
        List<SourceFile> many = new ArrayList<>();
        for (int i = 0; i < CodeMapBuilder.MAX_FILES + 5; i++) many.add(f("src/f" + i + ".ts", "export const x = 1;"));
        many.add(f("src/huge.ts", "x".repeat(CodeMapBuilder.MAX_FILE_BYTES + 1)));
        CodeMap big = new CodeMapBuilder().build(many);
        assertTrue(big.stats().truncated());
        assertEquals(CodeMapBuilder.MAX_FILES, big.stats().files());
        assertNull(big.file("src/huge.ts"));
    }
}
