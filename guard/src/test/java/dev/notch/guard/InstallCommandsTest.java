package dev.notch.guard;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InstallCommandsTest {

    private static List<String> lines(String command) {
        return InstallCommands.parse(command).stream().flatMap(c -> c.added().stream().map(l -> c.path() + " | " + l.text())).toList();
    }

    @Test
    void readsNpmFamilyInstalls() {
        assertEquals(List.of("package.json | \"mongoose\": \"*\",", "package.json | \"@apollo/server\": \"*\","),
                lines("npm install --save mongoose@8.4.0 @apollo/server@4"));
        assertEquals(List.of("package.json | \"kafkajs\": \"*\","), lines("pnpm add -D kafkajs"));
        assertEquals(List.of("package.json | \"ioredis\": \"*\","), lines("yarn add ioredis"));
    }

    @Test
    void readsPythonAndOtherEcosystems() {
        assertEquals(List.of("requirements.txt | pymongo"), lines("pip install pymongo==4.8 -r requirements.txt"));
        assertEquals(List.of("requirements.txt | pymongo"), lines("python -m pip install 'pymongo>=4'"));
        assertEquals(List.of("pyproject.toml | psycopg = \"*\""), lines("poetry add psycopg"));
        assertEquals(List.of("Cargo.toml | redis = \"*\""), lines("cargo add redis"));
        assertEquals(List.of("docker-compose.yml | image: mongo"), lines("docker run -d -p 27017:27017 mongo:7"));
    }

    @Test
    void chainedCommandsAreAllRead() {
        assertEquals(2, lines("cd api && npm i mongoose; pip install pymongo").size());
    }

    @Test
    void nonInstallCommandsGiveNothing() {  // boundary
        assertTrue(lines("npm test").isEmpty());
        assertTrue(lines("npm install").isEmpty());
        assertTrue(lines("ls -la && git status").isEmpty());
        assertTrue(lines("pip install -e .").isEmpty());
        assertTrue(lines("").isEmpty());
        assertTrue(lines(null).isEmpty());
    }

    @Test
    void anInstallOfARuledOutToolIsCaughtByTheGuard() {
        Decision pg = new Decision("d1", "Let's go with Postgres instead of MongoDB.", List.of("MongoDB"), Instant.parse("2026-10-05T10:00:00Z"));
        List<GuardFinding> f = new DiffGuard().check(List.of(pg), InstallCommands.parse("npm install mongoose"));
        assertEquals(1, f.size());
        assertEquals(GuardFinding.Severity.HIGH, f.get(0).severity());
    }
}
