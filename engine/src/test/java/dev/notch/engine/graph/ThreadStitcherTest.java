package dev.notch.engine.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ThreadStitcherTest {

    @Test
    void conversationsSharingAKeyJoinOneThread() {
        ThreadStitcher s = new ThreadStitcher();
        s.addItem("chat-1", List.of("src/auth/login.ts"));
        s.addItem("chat-2", List.of("src/auth/login.ts", "ECONNREFUSED"));
        assertTrue(s.sameThread("chat-1", "chat-2"));
        assertEquals(1, s.threadCount());
    }

    @Test
    void linksAreTransitive() {
        ThreadStitcher s = new ThreadStitcher();
        s.addItem("a", List.of("file-x"));
        s.addItem("b", List.of("file-x", "decision:cache"));
        s.addItem("c", List.of("decision:cache"));
        s.addItem("d", List.of("unrelated"));
        assertTrue(s.sameThread("a", "c"));
        assertFalse(s.sameThread("a", "d"));
        assertEquals(Map.of("a", List.of("a", "b", "c"), "d", List.of("d")), s.threads());
        assertEquals("a", s.threadOf("c"));
    }

    @Test
    void ignoredKeysDoNotLink() {
        ThreadStitcher s = new ThreadStitcher(Set.of("README.md"));
        s.addItem("a", List.of("readme.md"));
        s.addItem("b", List.of("README.md "));
        assertFalse(s.sameThread("a", "b"));
    }

    @Test
    void itemWithoutKeysIsItsOwnThread() {  // boundary
        ThreadStitcher s = new ThreadStitcher();
        s.addItem("solo", List.of());
        s.addItem("blank", java.util.Arrays.asList("  ", null));
        assertEquals(2, s.threadCount());
        assertEquals("solo", s.threadOf("solo"));
    }

    @Test
    void keysAreNormalized() {
        ThreadStitcher s = new ThreadStitcher();
        s.addItem("a", List.of("  Src/App.TSX"));
        s.addItem("b", List.of("src/app.tsx"));
        assertTrue(s.sameThread("a", "b"));
    }

    @Test
    void rejectsDuplicateAndUnknownItems() {  // invalid input
        ThreadStitcher s = new ThreadStitcher();
        s.addItem("a", List.of("k"));
        assertThrows(IllegalArgumentException.class, () -> s.addItem("a", List.of()));
        assertThrows(IllegalArgumentException.class, () -> s.sameThread("a", "zzz"));
        assertThrows(IllegalArgumentException.class, () -> s.threadOf("zzz"));
    }
}
