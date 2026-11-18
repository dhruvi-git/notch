package dev.notch.engine.bench;

import dev.notch.engine.baseline.Baselines;
import dev.notch.engine.flow.SeatAssigner;
import dev.notch.engine.graph.DisjointSetUnion;
import dev.notch.engine.probabilistic.BloomFilter;
import dev.notch.engine.score.PercentileBands;
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

    /** Rows per section, kept for {@code --json} output. */
    private static final Map<String, List<String>> JSON = new java.util.LinkedHashMap<>();

    private static void record(String section, String format, Object... args) {
        JSON.computeIfAbsent(section, k -> new ArrayList<>()).add(String.format(Locale.ROOT, format, args));
    }

    /**
     * Prints Markdown tables, or with {@code --json} a JSON document for the web app's Lab page:
     * {@code java -cp engine/target/classes dev.notch.engine.bench.BenchmarkRunner --json > web/src/data/benchmarks.json}
     */
    public static void main(String[] args) {
        boolean json = java.util.Arrays.asList(args).contains("--json");
        java.io.PrintStream out = System.out;
        if (json) System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        run();
        System.setOut(out);
        if (json) {
            StringBuilder sb = new StringBuilder("{\n");
            sb.append(String.format(Locale.ROOT, "  \"java\": \"%s\", \"warmup\": %d, \"runs\": %d, \"seed\": %d, \"generatedAt\": \"%s\",%n",
                    System.getProperty("java.version"), WARMUP, RUNS, SEED, java.time.Instant.now()));
            int i = 0;
            for (Map.Entry<String, List<String>> e : JSON.entrySet()) {
                sb.append("  \"").append(e.getKey()).append("\": [\n    ")
                        .append(String.join(",\n    ", e.getValue())).append("\n  ]")
                        .append(++i < JSON.size() ? ",\n" : "\n");
            }
            out.print(sb.append("}\n"));
        }
    }

    private static void run() {
        System.out.println("# Notch engine benchmarks");
        System.out.printf(Locale.ROOT, "Java %s, %d warm-up + %d timed runs, median reported, seed %d%n%n",
                System.getProperty("java.version"), WARMUP, RUNS, SEED);
        exactSearch();
        autocomplete();
        topK();
        threading();
        bloomFilter();
        seatAssignment();
        percentileBands();
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
            record("exactSearch", "{\"n\": %d, \"buildMs\": %.2f, \"fastMs\": %.3f, \"slowMs\": %.2f}", n, build, fast, slow);
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
            record("autocomplete", "{\"n\": %d, \"fastMs\": %.3f, \"slowMs\": %.2f, \"nodes\": %d, \"chars\": %d}",
                    n, fast, slow, tree.nodeCount(), chars);
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
            record("topK", "{\"n\": %d, \"fastMs\": %.3f, \"slowMs\": %.2f}", n, fast, slow);
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
            record("threading", "{\"n\": %d, \"fastUs\": %.4f, \"slowUs\": %.2f}", n, dsuPerInsertUs, bfsPerInsertUs);
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
            record("bloom", "{\"n\": %d, \"bits\": %d, \"hashes\": %d, \"theoreticalFpr\": %.5f, \"measuredFpr\": %.5f, "
                    + "\"memoryBytes\": %d, \"rawBytes\": %d}", n, f.bits(), f.hashes(), f.expectedFpp(), (double) fp / probes,
                    f.memoryBytes(), 64L * n);
        }
        System.out.println();
    }

    // ---------------------------------------------------------------- seat assignment

    /** Seats per role as a share of the pool, in the synthetic drive below. */
    static final double[] SEAT_SHARE = {0.10, 0.10, 0.05, 0.05, 0.05};

    /**
     * A synthetic campus drive with five roles and threshold 70. A fifth of the pool are
     * all-rounders: they qualify for every role and score highest on role 0, the popular one.
     * The rest are specialists who qualify only for role 0 or role 1. Roles 2 to 4 can only be
     * filled by all-rounders, which is exactly what greedy gets wrong: it spends all-rounders on
     * role 0, where specialists would have done, and leaves the niche roles short.
     */
    static double[][] driveScores(int n, Random rnd) {
        double[][] s = new double[n][SEAT_SHARE.length];
        for (int i = 0; i < n; i++) {
            if (rnd.nextInt(5) == 0) {
                s[i][0] = 90 + rnd.nextInt(11);
                for (int j = 1; j < s[i].length; j++) s[i][j] = 70 + rnd.nextInt(20);
            } else {
                for (int j = 0; j < s[i].length; j++) s[i][j] = rnd.nextInt(60);
                s[i][rnd.nextInt(2)] = 70 + rnd.nextInt(20);
            }
        }
        return s;
    }

    private static void seatAssignment() {
        System.out.println("## Seat assignment: Edmonds–Karp max-flow vs. greedy");
        System.out.println("Synthetic campus drive: 5 roles with seats for 10%, 10%, 5%, 5% and 5% of the pool, threshold 70;");
        System.out.println("a fifth of candidates qualify for every role, the rest for one popular role. Seats filled is the quality measure.\n");
        System.out.println("| Candidates | Seats | Greedy filled | Max-flow filled | Greedy (ms) | Max-flow, cold start (ms) | Max-flow, greedy start (ms) | Augmenting paths (cold / warm) |");
        System.out.println("|---:|---:|---:|---:|---:|---:|---:|---:|");
        for (int n : SIZES) {
            double[][] scores = driveScores(n, new Random(SEED));
            List<SeatAssigner.Role> rs = new ArrayList<>();
            for (int j = 0; j < SEAT_SHARE.length; j++) rs.add(new SeatAssigner.Role("role" + j, (int) (n * SEAT_SHARE[j]), 70));
            List<String> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) ids.add("c" + i);

            SeatAssigner.Result greedy = SeatAssigner.greedy(rs, ids, scores);
            SeatAssigner.Result cold = SeatAssigner.assign(rs, ids, scores, false);
            SeatAssigner.Result warm = SeatAssigner.assign(rs, ids, scores, true);
            if (cold.seatsFilled() != warm.seatsFilled()) throw new IllegalStateException("cold and warm starts disagree");
            boolean big = n >= 100_000;
            double greedyMs = median(() -> SeatAssigner.greedy(rs, ids, scores), big ? 1 : WARMUP, big ? 3 : RUNS);
            double coldMs = median(() -> SeatAssigner.assign(rs, ids, scores, false), big ? 0 : WARMUP, big ? 1 : RUNS);
            double warmMs = median(() -> SeatAssigner.assign(rs, ids, scores, true), big ? 1 : WARMUP, big ? 3 : RUNS);
            System.out.printf(Locale.ROOT, "| %,d | %,d | %,d | %,d | %.1f | %.1f | %.1f | %,d / %,d |%n", n, cold.totalSeats(),
                    greedy.seatsFilled(), cold.seatsFilled(), greedyMs, coldMs, warmMs, cold.augmentations(), warm.augmentations());
            record("seats", "{\"n\": %d, \"seats\": %d, \"greedyFilled\": %d, \"flowFilled\": %d, \"greedyMs\": %.2f, "
                    + "\"coldMs\": %.2f, \"warmMs\": %.2f, \"coldPaths\": %d, \"warmPaths\": %d}", n, cold.totalSeats(),
                    greedy.seatsFilled(), cold.seatsFilled(), greedyMs, coldMs, warmMs, cold.augmentations(), warm.augmentations());
        }
        System.out.println();
    }

    // ---------------------------------------------------------------- percentile bands

    private static void percentileBands() {
        System.out.println("## Percentile bands: randomized Quickselect vs. full sort");
        System.out.println("The four badge cut-offs (99th, 95th, 90th, 75th percentile) of n skill scores.\n");
        System.out.println("| Scores | Quickselect (ms) | Full sort (ms) | Speed-up |");
        System.out.println("|---:|---:|---:|---:|");
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000};
        for (int n : sizes) {
            double[] scores = new Random(SEED).doubles(n, 0, 100).toArray();
            RandomizedSelect sel = new RandomizedSelect(SEED);
            double fast = median(() -> PercentileBands.of(scores, sel).cutoff(1));
            double slow = median(() -> {
                double[] sorted = scores.clone();
                Arrays.sort(sorted);
                double sink = 0;
                for (int band : PercentileBands.BANDS) sink += sorted[(int) Math.ceil((100 - band) / 100.0 * n) - 1];
                return sink;
            });
            System.out.printf(Locale.ROOT, "| %,d | %.2f | %.2f | %.1fx |%n", n, fast, slow, slow / fast);
            record("bands", "{\"n\": %d, \"fastMs\": %.3f, \"slowMs\": %.3f}", n, fast, slow);
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
