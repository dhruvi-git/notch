package dev.notch.engine.flow;

import java.util.Arrays;

/**
 * Maximum flow by Edmonds–Karp: Ford–Fulkerson where every augmenting path is a shortest path
 * (fewest edges) in the residual graph, found by breadth-first search.
 *
 * <p>The graph is stored as an edge list with paired reverse edges: edge {@code e} and edge
 * {@code e ^ 1} are each other's reverse, so pushing flow along one frees capacity on the
 * other. Each node keeps a singly linked list of its outgoing edges ({@code head}/{@code next}),
 * which keeps the structure in a few primitive arrays.
 *
 * <p>Edges leaving a node are explored in the order they were added. Callers that add a
 * node's edges best-first (as {@link SeatAssigner} does) make the search prefer those edges
 * among equally short paths.
 *
 * <p>Time: O(V E²) in general; O(F E) when the maximum flow F is small, because every
 * augmentation adds at least one unit. Space: O(V + E).
 */
public final class MaxFlow {

    private final int n;
    private final int[] head;
    private int[] to;
    private int[] next;
    private long[] cap;      // residual capacity
    private long[] original; // capacity as added (0 for reverse edges)
    private int edges;
    private int augmentations;
    private boolean solved;

    /** A network with nodes {@code 0 .. nodes-1} and no edges. */
    public MaxFlow(int nodes) {
        if (nodes < 2) throw new IllegalArgumentException("a flow network needs at least 2 nodes");
        this.n = nodes;
        this.head = new int[nodes];
        Arrays.fill(head, -1);
        int initial = 16;
        this.to = new int[initial];
        this.next = new int[initial];
        this.cap = new long[initial];
        this.original = new long[initial];
    }

    /**
     * Adds a directed edge {@code u -> v} with the given capacity and returns its id, which
     * {@link #flowOn(int)} accepts after {@link #maxFlow(int, int)} has run.
     */
    public int addEdge(int u, int v, long capacity) {
        if (solved) throw new IllegalStateException("edges cannot be added after maxFlow has run");
        checkNode(u);
        checkNode(v);
        if (u == v) throw new IllegalArgumentException("self-loops are not allowed: " + u);
        if (capacity < 0) throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        ensureRoom(edges + 2);
        int e = edges;
        link(e, u, v, capacity);
        link(e + 1, v, u, 0);
        edges += 2;
        return e;
    }

    private void link(int e, int from, int target, long c) {
        to[e] = target;
        cap[e] = c;
        original[e] = c;
        next[e] = head[from];
        head[from] = e;
    }

    /**
     * Augments to a maximum flow from {@code s} to {@code t} and returns the value added by
     * augmenting paths (the whole maximum flow unless {@link #push} pre-loaded some).
     * Can be called once per network.
     */
    public long maxFlow(int s, int t) {
        checkNode(s);
        checkNode(t);
        if (s == t) throw new IllegalArgumentException("source and sink must differ");
        if (solved) throw new IllegalStateException("maxFlow has already run on this network");
        solved = true;
        reverseAdjacency();

        long flow = 0;
        int[] parentEdge = new int[n];
        int[] queue = new int[n];
        while (true) {
            Arrays.fill(parentEdge, -1);
            // Breadth-first search for a shortest augmenting path.
            int qh = 0, qt = 0;
            queue[qt++] = s;
            parentEdge[s] = -2; // marks the source as visited
            while (qh < qt && parentEdge[t] == -1) {
                int u = queue[qh++];
                for (int e = head[u]; e != -1; e = next[e]) {
                    int v = to[e];
                    if (cap[e] > 0 && parentEdge[v] == -1) {
                        parentEdge[v] = e;
                        queue[qt++] = v;
                        if (v == t) break;
                    }
                }
            }
            if (parentEdge[t] == -1) return flow; // no augmenting path: the flow is maximum

            // Bottleneck: the smallest residual capacity on the path.
            long bottleneck = Long.MAX_VALUE;
            for (int v = t; v != s; v = to[parentEdge[v] ^ 1]) {
                bottleneck = Math.min(bottleneck, cap[parentEdge[v]]);
            }
            for (int v = t; v != s; v = to[parentEdge[v] ^ 1]) {
                int e = parentEdge[v];
                cap[e] -= bottleneck;
                cap[e ^ 1] += bottleneck;
            }
            flow += bottleneck;
            augmentations++;
        }
    }

    /**
     * Sends {@code amount} units along an edge before {@link #maxFlow} runs, to start from a
     * known feasible flow (a warm start). The caller must keep the starting flow feasible:
     * conserved at every node except source and sink, as when pushing along whole s-t paths.
     * Ford–Fulkerson's argument holds from any feasible flow, so the result is still maximum.
     */
    public void push(int edge, long amount) {
        if (solved) throw new IllegalStateException("push must come before maxFlow");
        if (edge < 0 || edge >= edges || (edge & 1) == 1) {
            throw new IllegalArgumentException("not an edge id returned by addEdge: " + edge);
        }
        if (amount < 0 || amount > cap[edge]) {
            throw new IllegalArgumentException("cannot push " + amount + " through residual capacity " + cap[edge]);
        }
        cap[edge] -= amount;
        cap[edge ^ 1] += amount;
    }

    /** Flow carried by an edge returned from {@link #addEdge}, after {@link #maxFlow} has run. */
    public long flowOn(int edge) {
        if (!solved) throw new IllegalStateException("run maxFlow first");
        if (edge < 0 || edge >= edges || (edge & 1) == 1) {
            throw new IllegalArgumentException("not an edge id returned by addEdge: " + edge);
        }
        return original[edge] - cap[edge];
    }

    /** Number of augmenting paths used by the last {@link #maxFlow} call. */
    public int augmentations() {
        return augmentations;
    }

    public int nodes() {
        return n;
    }

    /** Number of edges added with {@link #addEdge} (reverse edges not counted). */
    public int edges() {
        return edges / 2;
    }

    /**
     * {@link #link} pushes each edge onto the front of its list, so lists end up newest first.
     * Reversing them once lets the search explore edges in the order they were added.
     */
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
    }
}
