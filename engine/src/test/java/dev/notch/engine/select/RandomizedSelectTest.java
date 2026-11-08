package dev.notch.engine.select;

import dev.notch.engine.baseline.Baselines;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RandomizedSelectTest {

    @Test
    void topKMatchesFullSortOnRandomData() {
        Random rnd = new Random(5);
        for (int t = 0; t < 200; t++) {
            List<Integer> data = new ArrayList<>();
            int n = 1 + rnd.nextInt(500);
            for (int i = 0; i < n; i++) data.add(rnd.nextInt(10_000));
            int k = 1 + rnd.nextInt(n);
            RandomizedSelect sel = new RandomizedSelect(t);
            assertEquals(Baselines.topKBySort(data, k, Comparator.naturalOrder()),
                    sel.topK(data, k, Comparator.naturalOrder()));
        }
    }

    @Test
    void handlesHeavyDuplicates() {
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 1000; i++) data.add(i % 3);
        List<Integer> top = new RandomizedSelect(1).topK(data, 400, Comparator.naturalOrder());
        assertEquals(400, top.size());
        assertEquals(333, top.stream().filter(x -> x == 2).count());
        assertEquals(67, top.stream().filter(x -> x == 1).count());
    }

    @Test
    void kLargerThanInputReturnsEverythingSorted() {  // boundary
        assertEquals(List.of(9, 4, 1), new RandomizedSelect(1).topK(List.of(4, 1, 9), 10, Comparator.naturalOrder()));
    }

    @Test
    void emptyInputAndSingleElement() {  // boundary
        RandomizedSelect sel = new RandomizedSelect(1);
        assertTrue(sel.topK(List.<Integer>of(), 3, Comparator.naturalOrder()).isEmpty());
        assertEquals(List.of(7), sel.topK(List.of(7), 1, Comparator.naturalOrder()));
    }

    @Test
    void rejectsInvalidInput() {
        RandomizedSelect sel = new RandomizedSelect(1);
        assertThrows(IllegalArgumentException.class, () -> sel.topK(List.of(1), 0, Comparator.<Integer>naturalOrder()));
        assertThrows(IllegalArgumentException.class, () -> sel.kthSmallest(new double[] {1, 2}, 2));
        assertThrows(IllegalArgumentException.class, () -> sel.percentile(new double[0], 0.5));
        assertThrows(IllegalArgumentException.class, () -> sel.percentile(new double[] {1}, 0));
    }

    @Test
    void doesNotModifyInput() {
        List<Integer> data = new ArrayList<>(List.of(3, 1, 2));
        new RandomizedSelect(1).topK(data, 2, Comparator.naturalOrder());
        assertEquals(List.of(3, 1, 2), data);
        double[] arr = {3, 1, 2};
        new RandomizedSelect(1).kthSmallest(arr, 0);
        assertArrayEquals(new double[] {3, 1, 2}, arr);
    }

    @Test
    void kthSmallestMatchesSortedOrder() {
        Random rnd = new Random(9);
        double[] values = rnd.doubles(1001).toArray();
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        RandomizedSelect sel = new RandomizedSelect(2);
        for (int k : new int[] {0, 1, 500, 999, 1000}) assertEquals(sorted[k], sel.kthSmallest(values, k));
    }

    @Test
    void percentileUsesNearestRank() {
        double[] v = {15, 20, 35, 40, 50};
        RandomizedSelect sel = new RandomizedSelect(3);
        assertEquals(20, sel.percentile(v, 0.4));
        assertEquals(35, sel.percentile(v, 0.5));
        assertEquals(50, sel.percentile(v, 1.0));
    }

    @Test
    void worksOnAlreadySortedAndReversedInput() {  // inputs that break fixed-pivot quicksort
        List<Integer> asc = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) asc.add(i);
        List<Integer> desc = new ArrayList<>(asc);
        java.util.Collections.reverse(desc);
        RandomizedSelect sel = new RandomizedSelect(4);
        assertEquals(List.of(99_999, 99_998, 99_997), sel.topK(asc, 3, Comparator.naturalOrder()));
        assertEquals(List.of(99_999, 99_998, 99_997), sel.topK(desc, 3, Comparator.naturalOrder()));
    }
}
