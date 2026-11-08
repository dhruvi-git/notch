package dev.notch.engine.graph;

import java.util.Arrays;

/**
 * Disjoint-set union (union-find) with union by rank and path halving.
 *
 * <p>A sequence of m operations on n elements takes O(m α(n)) time, where α is the inverse
 * Ackermann function, effectively constant. Elements are added one at a time, so the
 * structure grows as conversations are imported.
 */
public final class DisjointSetUnion {

    private int[] parent;
    private byte[] rank;
    private int[] size;
    private int count;
    private int components;

    public DisjointSetUnion() {
        this(16);
    }

    public DisjointSetUnion(int initialCapacity) {
        if (initialCapacity < 0) throw new IllegalArgumentException("capacity must be >= 0");
        int cap = Math.max(1, initialCapacity);
        parent = new int[cap];
        rank = new byte[cap];
        size = new int[cap];
    }

    /** Creates {@code n} singleton elements, ids 0..n-1. */
    public static DisjointSetUnion ofSize(int n) {
        DisjointSetUnion d = new DisjointSetUnion(n);
        for (int i = 0; i < n; i++) d.add();
        return d;
    }

    /** Adds a new singleton element and returns its id. */
    public int add() {
        if (count == parent.length) {
            int cap = parent.length * 2;
            parent = Arrays.copyOf(parent, cap);
            rank = Arrays.copyOf(rank, cap);
            size = Arrays.copyOf(size, cap);
        }
        int id = count++;
        parent[id] = id;
        size[id] = 1;
        components++;
        return id;
    }

    /** The representative of {@code x}'s set. Path halving points nodes at their grandparents. */
    public int find(int x) {
        check(x);
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    /** Merges the sets of {@code a} and {@code b}. Returns false if they were already joined. */
    public boolean union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra == rb) return false;
        if (rank[ra] < rank[rb]) {
            int t = ra;
            ra = rb;
            rb = t;
        }
        parent[rb] = ra;
        size[ra] += size[rb];
        if (rank[ra] == rank[rb]) rank[ra]++;
        components--;
        return true;
    }

    public boolean connected(int a, int b) {
        return find(a) == find(b);
    }

    /** Number of elements in {@code x}'s set. */
    public int componentSize(int x) {
        return size[find(x)];
    }

    public int componentCount() {
        return components;
    }

    public int size() {
        return count;
    }

    private void check(int x) {
        if (x < 0 || x >= count) {
            throw new IndexOutOfBoundsException("element " + x + " not in [0, " + count + ")");
        }
    }
}
