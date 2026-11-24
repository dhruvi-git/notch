package dev.notch.engine.flow;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Minimum-cost maximum flow by successive shortest paths: repeatedly send flow along the
 * cheapest augmenting path in the residual graph until no augmenting path is left.
 *
 * <p>Each augmentation keeps the flow a cheapest one for its value, because a flow is of
 * minimum cost exactly when its residual graph has no negative-cost cycle, and pushing along a
 * shortest path never creates one. When the loop stops the flow is maximum, so the result is
 * the cheapest of all maximum flows.
 *
 * <p>Edge costs may be negative ({@link SeatAssigner#bestFit} uses minus the match score, to
 * maximize total score), so shortest paths start with one Bellman–Ford pass. Its distances
 * become Johnson potentials {@code π}: with reduced costs {@code c(u,v) + π(u) − π(v)} every
 * residual edge costs at least 0, so each later search is Dijkstra with a binary heap. After
 * each search the potentials grow by the new distances, which keeps reduced costs non-negative
 * on the edges the augmentation opens up. The network must not contain a negative cycle.
 *
 * <p>Same edge-list layout as {@link MaxFlow}: edge {@code e} and {@code e ^ 1} are reverses,
 * and a reverse edge costs minus its forward edge.
 *
 * <p>Time: O(V E) for Bellman–Ford, then O(F · E log V) for F units of flow. Space: O(V + E).
 */
public final class MinCostFlow {

    private static final long INF = Long.MAX_VALUE / 4;

    private final int n;
    private final int[] head;
    private int[] to;
    private int[] next;
    private long[] cap;      // residual capacity
    private long[] original; // capacity as added (0 for reverse edges)
    private long[] cost;
    private int edges;
    private int augmentations;
    private boolean solved;

    /** A network with nodes {@code 0 .. nodes-1} and no edges. */
    public MinCostFlow(int nodes) {
        if (nodes < 2) throw new IllegalArgumentException("a flow network needs at least 2 nodes");
        this.n = nodes;
        this.head = new int[nodes];
        Arrays.fill(head, -1);
        int initial = 16;
        this.to = new int[initial];
        this.next = new int[initial];
        this.cap = new long[initial];
        this.original = new long[initial];
        this.cost = new long[initial];
    }

    /** Adds {@code u -> v} with a capacity and a cost per unit of flow; returns the edge id. */
    public int addEdge(int u, int v, long capacity, long unitCost) {
        if (solved) throw new IllegalStateException("edges cannot be added after solve has run");
        checkNode(u);
        checkNode(v);
        if (u == v) throw new IllegalArgumentException("self-loops are not allowed: " + u);
        if (capacity < 0) throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        ensureRoom(edges + 2);
        int e = edges;
        link(e, u, v, capacity, unitCost);
        link(e + 1, v, u, 0, -unitCost);
        edges += 2;
        return e;
    }

    private void link(int e, int from, int target, long c, long unitCost) {
        to[e] = target;
        cap[e] = c;
        original[e] = c;
        cost[e] = unitCost;
        next[e] = head[from];
        head[from] = e;
    }

    /** The result of {@link #solve}: the maximum flow and the least total cost it can be sent at. */
    public record Result(long flow, long cost) {
    }

    /** Sends a maximum flow from {@code s} to {@code t} at minimum total cost. Can be called once. */
    public Result solve(int s, int t) {
        checkNode(s);
        checkNode(t);
        if (s == t) throw new IllegalArgumentException("source and sink must differ");
        if (solved) throw new IllegalStateException("solve has already run on this network");
        solved = true;
        reverseAdjacency();

        long[] potential = bellmanFord(s);
        long[] dist = new long[n];
        int[] parentEdge = new int[n];
        long flow = 0, total = 0;
        while (true) {
            // Dijkstra on reduced costs, which are non-negative under the current potentials.
            Arrays.fill(dist, INF);
            Arrays.fill(parentEdge, -1);
            dist[s] = 0;
            PriorityQueue<long[]> heap = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
            heap.add(new long[]{0, s});
            while (!heap.isEmpty()) {
                long[] top = heap.poll();
                int u = (int) top[1];
                if (top[0] > dist[u]) continue; // stale entry
                for (int e = head[u]; e != -1; e = next[e]) {
                    int v = to[e];
                    if (cap[e] == 0 || potential[v] >= INF) continue;
                    long d = dist[u] + cost[e] + potential[u] - potential[v];
                    if (d < dist[v]) {
                        dist[v] = d;
                        parentEdge[v] = e;
                        heap.add(new long[]{d, v});
                    }
                }
            }
            if (dist[t] >= INF) return new Result(flow, total); // no augmenting path: maximum flow
            for (int v = 0; v < n; v++) if (dist[v] < INF) potential[v] += dist[v];

            long bottleneck = Long.MAX_VALUE;
            for (int v = t; v != s; v = to[parentEdge[v] ^ 1]) bottleneck = Math.min(bottleneck, cap[parentEdge[v]]);
            for (int v = t; v != s; v = to[parentEdge[v] ^ 1]) {
                int e = parentEdge[v];
                cap[e] -= bottleneck;
                cap[e ^ 1] += bottleneck;
                total += bottleneck * cost[e];
            }
            flow += bottleneck;
            augmentations++;
        }
    }

    /** Shortest-path distances from {@code s} over edges with capacity; {@code INF} where unreachable. */
    private long[] bellmanFord(int s) {
        long[] d = new long[n];
        Arrays.fill(d, INF);
        d[s] = 0;
        for (int round = 0; round < n - 1; round++) {
            boolean changed = false;
            for (int e = 0; e < edges; e++) {
                if (cap[e] == 0) continue;
                int u = to[e ^ 1];
                if (d[u] >= INF) continue;
                if (d[u] + cost[e] < d[to[e]]) {
                    d[to[e]] = d[u] + cost[e];
                    changed = true;
                }
            }
            if (!changed) return d;
        }
        for (int e = 0; e < edges; e++) {
            if (cap[e] > 0 && d[to[e ^ 1]] < INF && d[to[e ^ 1]] + cost[e] < d[to[e]]) {
                throw new IllegalArgumentException("the network has a negative-cost cycle");
            }
        }
        return d;
    }

    /** Flow carried by an edge returned from {@link #addEdge}, after {@link #solve} has run. */
    public long flowOn(int edge) {
        if (!solved) throw new IllegalStateException("run solve first");
        if (edge < 0 || edge >= edges || (edge & 1) == 1) {
            throw new IllegalArgumentException("not an edge id returned by addEdge: " + edge);
        }
        return original[edge] - cap[edge];
    }

    /** Number of augmenting paths used by {@link #solve}. */
    public int augmentations() {
        return augmentations;
    }

    /** Lists are built newest first; reversing them lets searches explore edges in the order added. */
    private void reverseAdjacency() {
        for (int u = 0; u < n; u++) {
            int prev = -1, e = head[u];
            while (e != -1) {
                int nx = next[e];
                next[e] = prev;
                prev = e;
                e = nx;
            }
            head[u] = prev;
        }
    }

    private void checkNode(int v) {
        if (v < 0 || v >= n) throw new IllegalArgumentException("node out of range: " + v);
    }

    private void ensureRoom(int needed) {
        if (needed <= to.length) return;
        int size = Math.max(needed, to.length * 2);
        to = Arrays.copyOf(to, size);
        next = Arrays.copyOf(next, size);
        cap = Arrays.copyOf(cap, size);
        original = Arrays.copyOf(original, size);
        cost = Arrays.copyOf(cost, size);
    }
}
