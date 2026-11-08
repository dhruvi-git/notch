package dev.notch.engine.baseline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Straightforward reference implementations. They are the baselines in benchmarks and the
 * oracles in randomized correctness tests.
 */
public final class Baselines {

    private Baselines() {
    }

    /** Every start position of {@code pattern} in {@code text}, by checking each position. O(nm). */
    public static int[] naiveFindAll(String text, String pattern) {
        if (pattern.isEmpty()) throw new IllegalArgumentException("pattern must not be empty");
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i + pattern.length() <= text.length(); i++) {
            if (text.startsWith(pattern, i)) out.add(i);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Suffix array by sorting suffix start positions with string comparison. O(n^2 log n) worst case. */
    public static int[] naiveSuffixArray(String text) {
        Integer[] idx = new Integer[text.length()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> text.substring(a).compareTo(text.substring(b)));
        return Arrays.stream(idx).mapToInt(Integer::intValue).toArray();
    }

    /** Keys starting with {@code prefix}, by scanning all keys and fully sorting. O(n log n). */
    public static List<String> linearPrefixScan(Map<String, Double> weights, String prefix, int k) {
        List<Map.Entry<String, Double>> hits = new ArrayList<>();
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            if (e.getKey().startsWith(prefix)) hits.add(e);
        }
        hits.sort(Map.Entry.<String, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, hits.size()); i++) out.add(hits.get(i).getKey());
        return out;
    }

    /** The k largest by full sort. O(n log n). */
    public static <T> List<T> topKBySort(Collection<? extends T> items, int k, Comparator<? super T> order) {
        List<T> copy = new ArrayList<>(items);
        copy.sort(order.reversed());
        return new ArrayList<>(copy.subList(0, Math.min(k, copy.size())));
    }

    /**
     * Connected components of n nodes joined by edges, recomputed from scratch with BFS.
     * Returns the component label of each node.
     */
    public static int[] bfsComponents(int n, List<int[]> edges) {
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (int[] e : edges) {
            adj.computeIfAbsent(e[0], x -> new ArrayList<>()).add(e[1]);
            adj.computeIfAbsent(e[1], x -> new ArrayList<>()).add(e[0]);
        }
        int[] label = new int[n];
        Arrays.fill(label, -1);
        int next = 0;
        Deque<Integer> queue = new ArrayDeque<>();
        for (int s = 0; s < n; s++) {
            if (label[s] != -1) continue;
            label[s] = next;
            queue.add(s);
            while (!queue.isEmpty()) {
                int u = queue.poll();
                for (int v : adj.getOrDefault(u, List.of())) {
                    if (label[v] == -1) {
                        label[v] = next;
                        queue.add(v);
                    }
                }
            }
            next++;
        }
        return label;
    }
}
