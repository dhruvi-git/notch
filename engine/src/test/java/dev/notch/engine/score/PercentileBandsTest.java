package dev.notch.engine.score;

import dev.notch.engine.select.RandomizedSelect;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.OptionalInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PercentileBandsTest {

    @Test
    void cutoffsMatchSortThenIndex() {
        Random rnd = new Random(99);
        for (int t = 0; t < 500; t++) {
            int n = PercentileBands.MIN_PEERS + rnd.nextInt(2000);
            double[] s = new double[n];
            for (int i = 0; i < n; i++) s[i] = Math.round(rnd.nextDouble() * 1000) / 10.0;
            PercentileBands bands = PercentileBands.of(s, new RandomizedSelect(t));

            double[] sorted = s.clone();
            Arrays.sort(sorted);
            for (int band : PercentileBands.BANDS) {
                double expected = sorted[Math.max(0, (int) Math.ceil((100 - band) / 100.0 * n) - 1)];
                if (expected > sorted[0]) assertEquals(expected, bands.cutoff(band), "band " + band + ", run " + t);
                else assertTrue(Double.isNaN(bands.cutoff(band)));
            }
        }
    }

    @Test
    void bandOfPicksTheTightestBandReached() {
        double[] s = new double[100];
        for (int i = 0; i < 100; i++) s[i] = i + 1; // 1..100
        PercentileBands b = PercentileBands.of(s, new RandomizedSelect(1));
        assertEquals(OptionalInt.of(1), b.bandOf(100));
        assertEquals(OptionalInt.of(5), b.bandOf(96));
        assertEquals(OptionalInt.of(10), b.bandOf(90));
        assertEquals(OptionalInt.of(25), b.bandOf(75));
        assertEquals(OptionalInt.empty(), b.bandOf(74));
    }

    @Test
    void edgeCaseTooFewPeersGivesNoBands() {
        PercentileBands b = PercentileBands.of(new double[]{10, 90, 99}, new RandomizedSelect(1));
        assertEquals(OptionalInt.empty(), b.bandOf(99));
        assertEquals(3, b.peers());
    }

    @Test
    void edgeCaseAllScoresEqualGivesNoBands() {
        double[] s = new double[500];
        Arrays.fill(s, 50);
        PercentileBands b = PercentileBands.of(s, new RandomizedSelect(1));
        assertEquals(OptionalInt.empty(), b.bandOf(50));
    }

    @Test
    void sameSeedSameCutoffs() {
        double[] s = new Random(3).doubles(10_000, 0, 100).toArray();
        PercentileBands a = PercentileBands.of(s, new RandomizedSelect(42));
        PercentileBands b = PercentileBands.of(s, new RandomizedSelect(42));
        for (int band : PercentileBands.BANDS) assertEquals(a.cutoff(band), b.cutoff(band));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(NullPointerException.class, () -> PercentileBands.of(null, new RandomizedSelect(1)));
        assertThrows(IllegalArgumentException.class, () -> PercentileBands.of(new double[]{50, 101}, new RandomizedSelect(1)));
        assertThrows(IllegalArgumentException.class, () -> PercentileBands.of(new double[]{Double.NaN}, new RandomizedSelect(1)));
        PercentileBands b = PercentileBands.of(new double[25], new RandomizedSelect(1));
        assertThrows(IllegalArgumentException.class, () -> b.cutoff(50));
    }
}
