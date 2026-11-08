package dev.notch.engine.graph;

import dev.notch.engine.baseline.Baselines;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DisjointSetUnionTest {

    @Test
    void startsWithSingletons() {
        DisjointSetUnion d = DisjointSetUnion.ofSize(5);
        assertEquals(5, d.componentCount());
        assertFalse(d.connected(0, 1));
        assertEquals(1, d.componentSize(3));
    }

    @Test
    void unionMergesTransitively() {
        DisjointSetUnion d = DisjointSetUnion.ofSize(4);
        assertTrue(d.union(0, 1));
        assertTrue(d.union(1, 2));
        assertTrue(d.connected(0, 2));
        assertFalse(d.connected(0, 3));
        assertEquals(2, d.componentCount());
        assertEquals(3, d.componentSize(2));
    }

    @Test
    void unionWithinSameSetReturnsFalse() {
        DisjointSetUnion d = DisjointSetUnion.ofSize(2);
        d.union(0, 1);
        assertFalse(d.union(1, 0));
        assertFalse(d.union(0, 0));
        assertEquals(1, d.componentCount());
    }

    @Test
    void outOfRangeElementsAreRejected() {  // boundary / invalid
        DisjointSetUnion d = DisjointSetUnion.ofSize(3);
        assertThrows(IndexOutOfBoundsException.class, () -> d.find(3));
        assertThrows(IndexOutOfBoundsException.class, () -> d.find(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> new DisjointSetUnion().find(0));
    }

    @Test
    void growsBeyondInitialCapacity() {  // boundary
        DisjointSetUnion d = new DisjointSetUnion(1);
        for (int i = 0; i < 1000; i++) d.add();
        assertEquals(1000, d.size());
        d.union(0, 999);
        assertTrue(d.connected(999, 0));
    }

    @Test
    void longChainStaysFast() {  // stress: path halving keeps finds shallow
        int n = 200_000;
        DisjointSetUnion d = DisjointSetUnion.ofSize(n);
        for (int i = 1; i < n; i++) d.union(i - 1, i);
        assertEquals(1, d.componentCount());
        assertEquals(n, d.componentSize(0));
        assertTrue(d.connected(0, n - 1));
    }

    @Test
    void matchesBfsComponentsOnRandomGraphs() {  // stress against the baseline
        Random rnd = new Random(3);
        for (int t = 0; t < 100; t++) {
            int n = 1 + rnd.nextInt(300);
            int m = rnd.nextInt(n * 2);
            List<int[]> edges = new ArrayList<>();
            DisjointSetUnion d = DisjointSetUnion.ofSize(n);
            for (int e = 0; e < m; e++) {
                int a = rnd.nextInt(n), b = rnd.nextInt(n);
                edges.add(new int[] {a, b});
                d.union(a, b);
            }
            int[] label = Baselines.bfsComponents(n, edges);
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < Math.min(n, i + 20); j++) {
                    assertEquals(label[i] == label[j], d.connected(i, j));
                }
            }
            assertEquals(java.util.Arrays.stream(label).max().orElse(-1) + 1, d.componentCount());
        }
    }
}
