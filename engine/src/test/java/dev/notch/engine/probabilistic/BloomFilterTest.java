package dev.notch.engine.probabilistic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {

    @Test
    void neverReturnsFalseNegatives() {
        BloomFilter f = BloomFilter.create(10_000, 0.01);
        for (int i = 0; i < 10_000; i++) f.add("chunk-" + i);
        for (int i = 0; i < 10_000; i++) assertTrue(f.mightContain("chunk-" + i));
    }

    @Test
    void empiricalFalsePositiveRateIsCloseToTheory() {
        int n = 10_000;
        BloomFilter f = BloomFilter.create(n, 0.01);
        for (int i = 0; i < n; i++) f.add("member-" + i);
        int trials = 200_000, falsePositives = 0;
        for (int i = 0; i < trials; i++) if (f.mightContain("outsider-" + i)) falsePositives++;
        double observed = (double) falsePositives / trials;
        double expected = f.expectedFpp();
        assertTrue(Math.abs(observed - expected) / expected < 0.2,
                "observed " + observed + " vs expected " + expected);
    }

    @Test
    void sizingFollowsTheFormulas() {
        // m = ceil(-1000 ln 0.01 / (ln 2)^2) = 9586, k = round(9586 / 1000 * ln 2) = 7
        BloomFilter f = BloomFilter.create(1000, 0.01);
        assertEquals(9586, f.bits());
        assertEquals(7, f.hashes());
    }

    @Test
    void emptyFilterContainsNothing() {  // boundary
        BloomFilter f = BloomFilter.create(100, 0.01);
        assertFalse(f.mightContain("anything"));
        assertFalse(f.mightContain(""));
        assertEquals(0, f.bitCount());
        assertEquals(0.0, f.expectedFpp());
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(100, 0));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(100, 1));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(0, 3));
        assertThrows(NullPointerException.class, () -> BloomFilter.create(10, 0.1).add((String) null));
    }

    @Test
    void overfillingRaisesTheFalsePositiveRate() {
        BloomFilter f = BloomFilter.create(100, 0.01);
        for (int i = 0; i < 100; i++) f.add("a" + i);
        double atCapacity = f.expectedFpp();
        for (int i = 0; i < 400; i++) f.add("b" + i);
        assertTrue(f.expectedFpp() > 10 * atCapacity);
    }

    @Test
    void clearResetsTheFilter() {
        BloomFilter f = BloomFilter.create(100, 0.01);
        f.add("x");
        f.clear();
        assertFalse(f.mightContain("x"));
        assertEquals(0, f.insertions());
    }

    @Test
    void usesFarLessMemoryThanStoringTheItems() {
        BloomFilter f = BloomFilter.create(100_000, 0.01);
        // About 1.2 bytes per item, versus 64-byte content hashes in a HashSet.
        assertTrue(f.memoryBytes() < 100_000 * 2);
    }
}
