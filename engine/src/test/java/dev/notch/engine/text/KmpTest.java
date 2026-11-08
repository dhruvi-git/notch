package dev.notch.engine.text;

import dev.notch.engine.baseline.Baselines;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class KmpTest {

    @Test
    void prefixFunctionOfAbacaba() {
        assertArrayEquals(new int[] {0, 0, 1, 0, 1, 2, 3}, Kmp.prefixFunction("abacaba"));
    }

    @Test
    void findsOverlappingMatches() {
        assertArrayEquals(new int[] {0, 1, 2}, Kmp.findAll("aaaa", "aa"));
    }

    @Test
    void returnsEmptyWhenAbsentOrPatternLonger() {  // boundary
        assertEquals(0, Kmp.findAll("hello", "xyz").length);
        assertEquals(0, Kmp.findAll("ab", "abc").length);
        assertEquals(0, Kmp.findAll("", "a").length);
    }

    @Test
    void matchAtVeryEnd() {  // boundary
        assertArrayEquals(new int[] {3}, Kmp.findAll("abcdef", "def"));
    }

    @Test
    void ignoreCase() {
        assertArrayEquals(new int[] {0, 10}, Kmp.findAll("TypeError typeerror", "typeerror", true));
    }

    @Test
    void indexOfStartsFromOffset() {
        assertEquals(4, Kmp.indexOf("abc abc", "abc", 1));
        assertEquals(-1, Kmp.indexOf("abc", "abc", 1));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> Kmp.findAll("abc", ""));
        assertThrows(IllegalArgumentException.class, () -> Kmp.indexOf("abc", "a", -1));
        assertThrows(NullPointerException.class, () -> Kmp.findAll(null, "a"));
    }

    @Test
    void matchesNaiveSearchOnRandomInput() {  // stress against the oracle
        Random rnd = new Random(7);
        for (int t = 0; t < 500; t++) {
            String text = SuffixArrayTest.randomString(rnd, rnd.nextInt(300), 2);
            String pattern = SuffixArrayTest.randomString(rnd, 1 + rnd.nextInt(6), 2);
            assertArrayEquals(Baselines.naiveFindAll(text, pattern), Kmp.findAll(text, pattern));
        }
    }
}
