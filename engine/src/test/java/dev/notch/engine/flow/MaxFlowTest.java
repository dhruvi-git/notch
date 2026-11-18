package dev.notch.engine.flow;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MaxFlowTest {

    @Test
    void textbookNetworkHasMaxFlow23() {
        // CLRS (3rd ed.) Figure 26.1: s=0, v1..v4 = 1..4, t=5.
        MaxFlow f = new MaxFlow(6);
        f.addEdge(0, 1, 16);
        f.addEdge(0, 2, 13);
        f.addEdge(1, 3, 12);
        f.addEdge(2, 1, 4);
        f.addEdge(2, 4, 14);
        f.addEdge(3, 2, 9);
        f.addEdge(3, 5, 20);
        f.addEdge(4, 3, 7);
        f.addEdge(4, 5, 4);
        assertEquals(23, f.maxFlow(0, 5));
    }

    @Test
    void needsReverseEdgeToReachTheMaximum() {
        // The shortest path s-a-b-t is found first; reaching 2 requires undoing a-b via its reverse edge.
        MaxFlow f = new MaxFlow(4); // s=0, a=1, b=2, t=3
        f.addEdge(0, 1, 1);
        f.addEdge(0, 2, 1);
        int ab = f.addEdge(1, 2, 1);
        f.addEdge(1, 3, 1);
        f.addEdge(2, 3, 1);
        assertEquals(2, f.maxFlow(0, 3));
        assertTrue(f.flowOn(ab) >= 0 && f.flowOn(ab) <= 1);
    }

    @Test
    void edgeCaseSinkUnreachableGivesZero() {
        MaxFlow f = new MaxFlow(4);
        f.addEdge(0, 1, 5);
        f.addEdge(2, 3, 5);
        assertEquals(0, f.maxFlow(0, 3));
        assertEquals(0, f.augmentations());
    }

    @Test
    void edgeCaseZeroCapacityAndParallelEdges() {
        MaxFlow f = new MaxFlow(2);
        f.addEdge(0, 1, 0);
        f.addEdge(0, 1, 3);
        f.addEdge(0, 1, 4);
        assertEquals(7, f.maxFlow(0, 1));
    }

    @Test
    void equalsMinimumCutOnRandomSmallGraphs() {
        // Max-flow min-cut: the maximum flow equals the smallest s-t cut, found here by trying every cut.
        Random rnd = new Random(26);
        for (int t = 0; t < 300; t++) {
            int n = 2 + rnd.nextInt(6);
            long[][] cap = new long[n][n];
            MaxFlow f = new MaxFlow(n);
            int[] ids = new int[n * n];
            for (int u = 0; u < n; u++) {
                for (int v = 0; v < n; v++) {
                    if (u != v && rnd.nextInt(3) == 0) {
                        long c = rnd.nextInt(10);
                        cap[u][v] += c;
                        ids[u * n + v] = f.addEdge(u, v, c) + 1; // +1 so 0 means "no edge"
                    }
                }
            }
            long flow = f.maxFlow(0, n - 1);
            assertEquals(minCut(cap, 0, n - 1), flow, "graph " + t);

            // Every edge respects its capacity and every inner node conserves flow.
            long[] net = new long[n];
            for (int u = 0; u < n; u++) {
                for (int v = 0; v < n; v++) {
                    if (ids[u * n + v] == 0) continue;
                    long fe = f.flowOn(ids[u * n + v] - 1);
                    assertTrue(fe >= 0 && fe <= cap[u][v]);
                    net[u] -= fe;
                    net[v] += fe;
                }
            }
            for (int v = 1; v < n - 1; v++) assertEquals(0, net[v], "conservation at " + v);
            assertEquals(flow, net[n - 1]);
        }
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new MaxFlow(1));
        MaxFlow f = new MaxFlow(3);
        assertThrows(IllegalArgumentException.class, () -> f.addEdge(0, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> f.addEdge(1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> f.addEdge(0, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> f.maxFlow(2, 2));
        assertThrows(IllegalStateException.class, () -> f.flowOn(0));
        f.addEdge(0, 2, 1);
        f.maxFlow(0, 2);
        assertThrows(IllegalStateException.class, () -> f.maxFlow(0, 2));
        assertThrows(IllegalStateException.class, () -> f.addEdge(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> f.flowOn(1)); // a reverse edge
    }

    private static long minCut(long[][] cap, int s, int t) {
        int n = cap.length;
        long best = Long.MAX_VALUE;
        for (int mask = 0; mask < (1 << n); mask++) {
            if ((mask >> s & 1) == 0 || (mask >> t & 1) == 1) continue;
            long cut = 0;
            for (int u = 0; u < n; u++) {
                if ((mask >> u & 1) == 0) continue;
                for (int v = 0; v < n; v++) if ((mask >> v & 1) == 0) cut += cap[u][v];
            }
            best = Math.min(best, cut);
        }
        return best;
    }
}
