package dev.notch.guard;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffGuardTest {

    private static final Instant DECIDED = Instant.parse("2026-10-05T10:00:00Z");
    private static final Decision POSTGRES = new Decision("d1",
            "Let's go with Postgres instead of MongoDB because we need joins.", List.of("MongoDB"), DECIDED);
    private static final Decision SESSIONS = new Decision("d2",
            "We chose server-side sessions instead of JWT because we need instant logout.", List.of("JWT"), DECIDED);

    private final DiffGuard guard = new DiffGuard();

    private List<GuardFinding> check(String path, String... lines) {
        return guard.check(List.of(POSTGRES, SESSIONS), List.of(DiffParser.fromContent(path, String.join("\n", lines), true)));
    }

    @Test
    void dependencyOnARuledOutToolIsHigh() {
        List<GuardFinding> f = check("package.json", "  \"mongoose\": \"^8.4.0\",");
        assertEquals(1, f.size());
        GuardFinding g = f.get(0);
        assertEquals(GuardFinding.Severity.HIGH, g.severity());
        assertEquals(GuardFinding.Kind.RULED_OUT, g.kind());
        assertEquals("MongoDB", g.tech());
        assertEquals("package.json:1", g.location());
        assertTrue(g.message().startsWith("Adds MongoDB (mongoose), but on 5 Oct 2026 the project chose PostgreSQL and ruled it out"));
    }

    @Test
    void importsAndConnectionStringsAreStrongSignals() {
        assertEquals(GuardFinding.Severity.HIGH, check("app.py", "from pymongo import MongoClient").get(0).severity());
        assertEquals(GuardFinding.Severity.HIGH, check("db.ts", "const url = 'mongodb://localhost:27017/app';").get(0).severity());
        assertEquals(GuardFinding.Severity.HIGH, check("auth.js", "const jwt = require('jsonwebtoken');").get(0).severity());
    }

    @Test
    void plainCodeMentionIsMedium() {
        List<GuardFinding> f = check("service.ts", "const store = new MongoStore(mongoClient);", "await saveToMongoDB(event);");
        assertTrue(f.isEmpty() || f.get(0).severity() == GuardFinding.Severity.MEDIUM);
        assertEquals(GuardFinding.Severity.MEDIUM, check("sync.go", "client := mongo.Connect(ctx)").get(0).severity());
    }

    @Test
    void differentToolOfTheSameKindIsMedium() {
        List<GuardFinding> f = check("pom.xml", "<artifactId>mysql-connector-j</artifactId>");
        assertEquals(1, f.size());
        assertEquals(GuardFinding.Kind.DIFFERENT_CHOICE, f.get(0).kind());
        assertEquals(GuardFinding.Severity.MEDIUM, f.get(0).severity());
        assertTrue(f.get(0).message().contains("database decision is PostgreSQL"));
    }

    @Test
    void theChosenToolIsFine() {
        assertTrue(check("package.json", "\"pg\": \"^8.11.0\",", "\"express-session\": \"^1.18.0\"").isEmpty());
    }

    @Test
    void commentsDocsLockfilesAndAgentFilesAreIgnored() {
        assertTrue(check("db.js", "// we use Postgres instead of MongoDB here").isEmpty());
        assertTrue(check("README.md", "We chose Postgres over MongoDB.").isEmpty());
        assertTrue(check("package-lock.json", "\"node_modules/mongoose\": {").isEmpty());
        assertTrue(check("AGENTS.md", "- Use Postgres (rejected: MongoDB)").isEmpty());
        assertTrue(check("requirements.txt", "# pymongo was removed").isEmpty());
    }

    @Test
    void wordBoundariesPreventFalseMatches() {
        assertTrue(check("utils.js", "const mongoose_free = mongooseless();").isEmpty());
        assertTrue(check("x.js", "const jwtLike = parse(token);").isEmpty());
    }

    @Test
    void oneFindingPerToolPerFile() {
        assertEquals(1, check("package.json", "\"mongoose\": \"8\",", "\"mongodb\": \"6\",").size());
    }

    @Test
    void noDecisionsOrNoChangesMeansNoFindings() {  // boundary
        assertTrue(guard.check(List.of(), List.of(DiffParser.fromContent("package.json", "\"mongoose\": \"8\"", true))).isEmpty());
        assertTrue(guard.check(List.of(POSTGRES), List.of()).isEmpty());
        Decision vague = new Decision("d3", "We decided to ship on Fridays.", List.of(), DECIDED);
        assertTrue(guard.check(List.of(vague), List.of(DiffParser.fromContent("package.json", "\"mongoose\": \"8\"", true))).isEmpty());
    }

    @Test
    void highFindingsComeFirst() {
        List<GuardFinding> f = guard.check(List.of(POSTGRES), List.of(
                DiffParser.fromContent("z.ts", "await saveToMongo(x)", true),
                DiffParser.fromContent("package.json", "\"mongoose\": \"8\"", true)));
        assertEquals(GuardFinding.Severity.HIGH, f.get(0).severity());
    }
}
