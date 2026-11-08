package dev.notch.engine.text;

import dev.notch.engine.baseline.Baselines;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SuffixArrayTest {

    @Test
    void sortsSuffixesOfBanana() {
        // Suffixes in order: a, ana, anana, banana, na, nana
        assertArrayEquals(new int[] {5, 3, 1, 0, 4, 2}, SuffixArray.build("banana").toArray());
    }

    @Test
    void findsOverlappingOccurrences() {
        SuffixArray sa = SuffixArray.build("banana");
        int[] hits = sa.occurrences("ana");
        Arrays.sort(hits);
        assertArrayEquals(new int[] {1, 3}, hits);
        assertEquals(3, sa.count("a"));
    }

    @Test
    void emptyTextHasNoSuffixesAndNoMatches() {  // boundary
        SuffixArray sa = SuffixArray.build("");
        assertEquals(0, sa.length());
        assertEquals(0, sa.count("x"));
    }

    @Test
    void singleCharacterText() {  // boundary
        SuffixArray sa = SuffixArray.build("z");
        assertArrayEquals(new int[] {0}, sa.toArray());
        assertEquals(1, sa.count("z"));
        assertEquals(0, sa.count("zz"));
    }

    @Test
    void patternLongerThanTextIsNotFound() {
        assertEquals(0, SuffixArray.build("abc").count("abcd"));
    }

    @Test
    void allEqualCharacters() {
        SuffixArray sa = SuffixArray.build("aaaaa");
        assertArrayEquals(new int[] {4, 3, 2, 1, 0}, sa.toArray());
        assertEquals(4, sa.count("aa"));
    }

    @Test
    void rejectsEmptyPatternAndNulCharacter() {  // invalid input
        SuffixArray sa = SuffixArray.build("abc");
        assertThrows(IllegalArgumentException.class, () -> sa.find(""));
        assertThrows(IllegalArgumentException.class, () -> SuffixArray.build("a\0b"));
        assertThrows(NullPointerException.class, () -> SuffixArray.build(null));
    }

    @Test
    void matchesNaiveImplementationOnRandomStrings() {  // stress against the oracle
        Random rnd = new Random(42);
        for (int trial = 0; trial < 300; trial++) {
            String text = randomString(rnd, 1 + rnd.nextInt(200), 1 + rnd.nextInt(4));
            SuffixArray sa = SuffixArray.build(text);
            assertArrayEquals(Baselines.naiveSuffixArray(text), sa.toArray(), text);
            String pattern = randomString(rnd, 1 + rnd.nextInt(4), 3);
            int[] got = sa.occurrences(pattern);
            Arrays.sort(got);
            assertArrayEquals(Baselines.naiveFindAll(text, pattern), got);
        }
    }

    @Test
    void handlesUnicodeAboveAscii() {
        SuffixArray sa = SuffixArray.build("naïve café naïve");
        assertEquals(2, sa.count("naïve"));
        assertEquals(1, sa.count("é"));
    }

    static String randomString(Random rnd, int len, int alphabet) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) ('a' + rnd.nextInt(alphabet)));
        return sb.toString();
    }
}
