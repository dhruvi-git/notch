package dev.notch.engine.bench;

import dev.notch.engine.baseline.Baselines;
import dev.notch.engine.graph.DisjointSetUnion;
import dev.notch.engine.probabilistic.BloomFilter;
import dev.notch.engine.select.RandomizedSelect;
import dev.notch.engine.text.CorpusIndex;
import dev.notch.engine.text.RadixTree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Compares each algorithm with its baseline at three input sizes and prints Markdown tables.
 *
 * <p>Each measurement is the median of several timed runs after warm-up runs (index builds use 1 warm-up and 3 runs), using
 * {@link System#nanoTime()}. Inputs are generated from a fixed seed, so every run sees the
 * same data. Run with: {@code java -cp engine/target/classes dev.notch.engine.bench.BenchmarkRunner}
 */
public final class BenchmarkRunner {

    private static final int[] SIZES = {1_000, 10_000, 100_000};
    private static final int WARMUP = 2;
    private static final int RUNS = 5;
    private static final long SEED = 2026;

    public static void main(String[] args) {
        System.out.println("# Notch engine benchmarks");
        System.out.printf(Locale.ROOT, "Java %s, %d warm-up + %d timed runs, median reported, seed %d%n%n",
                System.getProperty("java.version"), WARMUP, RUNS, SEED);
        exactSearch();
        autocomplete();
        topK();
        threading();
        bloomFilter();
    }

    // ---------------------------------------------------------------- exact search

    private static void exactSearch() {
        System.out.println("## Exact search: suffix array vs. scanning every chunk");
        System.out.println("200 queries per run. Chunks average about 200 characters.\n");
        System.out.println("| Chunks | Index build (ms) | Suffix array (ms) | Naive scan (ms) | Speed-up |");
        System.out.println("|---:|---:|---:|---:|---:|");
        for (int n : SIZES) {
            Random rnd = new Random(SEED);
            List<String> chunks = Synthetic.chunks(rnd, n);
            List<CorpusIndex.Doc> docs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) docs.add(new CorpusIndex.Doc("c" + i, chunks.get(i)));
            List<String> queries = Synthetic.queries(rnd, 200);
            List<String> lowerChunks = chunks.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();

            double build = median(() -> CorpusIndex.build(docs), 1, 3);
            CorpusIndex index = CorpusIndex.build(docs);
            double fast = median(() -> {
                int total = 0;
                for (String q : queries) total += index.count(q);
                return total;
            });
            double slow = median(() -> {
                int total = 0;
                for (String q : queries) {
                    String lq = q.toLowerCase(Locale.ROOT);
                    for (String c : lowerChunks) {
                        for (int at = c.indexOf(lq); at >= 0; at = c.indexOf(lq, at + 1)) total++;
                    }
                }
                return total;
            });
            row(n, build, fast, slow);
        }
        System.out.println();
    }

    // ---------------------------------------------------------------- autocomplete

    private static void autocomplete() {
        System.out.println("## Autocomplete: radix tree vs. linear prefix scan");
        System.out.println("1,000 prefix queries per run, top 10 by weight.\n");
        System.out.println("| Keys | Radix tree (ms) | Linear scan (ms) | Speed-up | Nodes vs. trie chars |");
        System.out.println("|---:|---:|---:|---:|---:|");
        for (int n : SIZES) {
            Random rnd = new Random(SEED);
            Map<String, Double> keys = new HashMap<>();
            while (keys.size() < n) keys.put(Synthetic.path(rnd), (double) rnd.nextInt(10_000));
            RadixTree tree = new RadixTree(new RandomizedSelect(SEED));
            keys.forEach(tree::insert);
            List<String> keyList = new ArrayList<>(keys.keySet());
            List<String> prefixes = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                String k = keyList.get(rnd.nextInt(keyList.size()));
                prefixes.add(k.substring(0, Math.min(k.length(), 6 + rnd.nextInt(10))));
            }
            double fast = median(() -> {
                int total = 0;
                for (String p : prefixes) total += tree.complete(p, 10).size();
                return total;
            });
            double slow = median(() -> {
                int total = 0;
                for (String p : prefixes) total += Baselines.linearPrefixScan(keys, p, 10).size();
                return total;
            });
            long chars = keys.keySet().stream().mapToLong(String::length).sum();
            System.out.printf(Locale.ROOT, "| %,d | %.2f | %.2f | %.1fx | %,d vs. %,d |%n",
                    n, fast, slow, slow / fast, tree.nodeCount(), chars);
        }
        System.out.println();
    }

    // ---------------------------------------------------------------- top-k

    private static void topK() {
        System.out.println("## Top-k selection: randomized Quickselect vs. full sort");
        System.out.println("Select the 20 best of n fused scores, 50 times per run.\n");
        System.out.println("| Scores | Quickselect (ms) | Full sort (ms) | Speed-up |");
        System.out.println("|---:|---:|---:|---:|");
        for (int n : SIZES) {
            Random rnd = new Random(SEED);
            List<Double> scores = new ArrayList<>(n);
            for (int i = 0; i < n; i++) scores.add(rnd.nextDouble());
            RandomizedSelect sel = new RandomizedSelect(SEED);
            double fast = median(() -> {
                double sink = 0;
                for (int r = 0; r < 50; r++) sink += sel.topK(scores, 20, Comparator.naturalOrder()).get(0);
                return sink;
            });
            double slow = median(() -> {
                double sink = 0;
                for (int r = 0; r < 50; r++) sink += Baselines.topKBySort(scores, 20, Comparator.naturalOrder()).get(0);
                return sink;
            });
            System.out.printf(Locale.ROOT, "| %,d | %.2f | %.2f | %.1fx |%n", n, fast, slow, slow / fast);
        }
        System.out.println();
    }

    // ---------------------------------------------------------------- threading

    private static void threading() {
        System.out.println("## Thread stitching: incremental union-find vs. BFS recompute");
        System.out.println("Cost of absorbing one new conversation once n already exist.\n");
        System.out.println("| Conversations | Union-find per insert (us) | BFS recompute per insert (us) | Speed-up |");
        System.out.println("|---:|---:|---:|---:|");
        for (int n : SIZES) {
            Random rnd = new Random(SEED);
            List<int[]> edges = new ArrayList<>();
            for (int i = 1; i < n; i++) {
                if (rnd.nextInt(3) > 0) edges.add(new int[] {i, rnd.nextInt(i)});
            }
            int inserts = 1000;
            double dsuMs = median(() -> {
                DisjointSetUnion d = DisjointSetUnion.ofSize(n + inserts);
                for (int[] e : edges) d.union(e[0], e[1]);
                long start = System.nanoTime();
                for (int i = 0; i < inserts; i++) d.union(n + i, rnd.nextInt(n));
                return (System.nanoTime() - start) / 1e6;
            }, true);
            double bfsMs = median(() -> {
                List<int[]> withNew = new ArrayList<>(edges);
                withNew.add(new int[] {n, rnd.nextInt(n)});
                return Baselines.bfsComponents(n + 1, withNew).length;
            });
            double dsuPerInsertUs = dsuMs * 1000 / inserts;
            double bfsPerInsertUs = bfsMs * 1000;
            System.out.printf(Locale.ROOT, "| %,d | %.3f | %.1f | %,.0fx |%n",
                    n, dsuPerInsertUs, bfsPerInsertUs, bfsPerInsertUs / dsuPerInsertUs);
        }
        System.out.println();
    }

    // ---------------------------------------------------------------- bloom filter

    private static void bloomFilter() {
        System.out.println("## Bloom filter: measured vs. theoretical false-positive rate");
        System.out.println("Sized for 1% at n items; probed with 200,000 items never inserted.\n");
        System.out.println("| Items | Bits | Hashes | Theoretical FPR | Measured FPR | Filter memory | Raw 64-byte hashes |");
        System.out.println("|---:|---:|---:|---:|---:|---:|---:|");
        for (int n : SIZES) {
            BloomFilter f = BloomFilter.create(n, 0.01);
            for (int i = 0; i < n; i++) f.add("chunk-hash-" + i);
            int probes = 200_000, fp = 0;
            for (int i = 0; i < probes; i++) if (f.mightContain("never-" + i)) fp++;
            System.out.printf(Locale.ROOT, "| %,d | %,d | %d | %.3f%% | %.3f%% | %s | %s |%n",
                    n, f.bits(), f.hashes(), f.expectedFpp() * 100, 100.0 * fp / probes,
                    bytes(f.memoryBytes()), bytes(64L * n));
        }
        System.out.println();
    }

    // ---------------------------------------------------------------- helpers

    private static double median(Supplier<?> work) {
        return median(work, WARMUP, RUNS);
    }

    private static double median(Supplier<?> work, int warmup, int runs) {
        for (int i = 0; i < warmup; i++) work.get();
        double[] times = new double[runs];
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            Object sink = work.get();
            times[i] = (System.nanoTime() - start) / 1e6;
            if (sink == null) throw new IllegalStateException();
        }
        Arrays.sort(times);
        return times[runs / 2];
    }

    /** For work that times only part of itself and returns elapsed milliseconds. */
    private static double median(Supplier<Double> selfTimed, boolean unused) {
        for (int i = 0; i < WARMUP; i++) selfTimed.get();
        double[] times = new double[RUNS];
        for (int i = 0; i < RUNS; i++) times[i] = selfTimed.get();
        Arrays.sort(times);
        return times[RUNS / 2];
    }

    private static void row(int n, double build, double fast, double slow) {
        System.out.printf(Locale.ROOT, "| %,d | %.1f | %.2f | %.1f | %.0fx |%n", n, build, fast, slow, slow / fast);
    }

    private static String bytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024));
    }
}
