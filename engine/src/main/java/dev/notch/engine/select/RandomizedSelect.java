package dev.notch.engine.select;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Randomized Quickselect with three-way partitioning.
 *
 * <p>Selecting the k-th element takes expected O(n) time regardless of input order, because
 * the pivot is chosen uniformly at random. Three-way partitioning keeps that bound when many
 * elements are equal. {@link #topK} returns the k largest elements in O(n + k log k) expected
 * time, instead of the O(n log n) of a full sort.
 *
 * <p>A seed makes runs reproducible for tests and benchmarks.
 */
public final class RandomizedSelect {

    private final Random rng;

    public RandomizedSelect() {
        this(new Random());
    }

    public RandomizedSelect(long seed) {
        this(new Random(seed));
    }

    private RandomizedSelect(Random rng) {
        this.rng = rng;
    }

    /**
     * The {@code k} largest items according to {@code order}, sorted largest first.
     * The input list is not modified.
     */
    public <T> List<T> topK(List<? extends T> items, int k, Comparator<? super T> order) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(order, "order");
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        Comparator<? super T> desc = Collections.reverseOrder(order);
        @SuppressWarnings("unchecked")
        T[] a = (T[]) items.toArray();
        int n = a.length;
        if (n == 0) return List.of();
        int take = Math.min(k, n);
        if (take < n) {
            selectInPlace(a, take - 1, desc);
        }
        Arrays.sort(a, 0, take, desc);
        List<T> out = new ArrayList<>(take);
        for (int i = 0; i < take; i++) out.add(a[i]);
        return out;
    }

    /**
     * Rearranges {@code a} so that position {@code target} holds the element a full sort by
     * {@code cmp} would place there, everything before it compares {@code <=} and everything
     * after compares {@code >=}.
     */
    <T> void selectInPlace(T[] a, int target, Comparator<? super T> cmp) {
        int lo = 0;
        int hi = a.length - 1;
        while (lo < hi) {
            T pivot = a[lo + rng.nextInt(hi - lo + 1)];
            // Invariant: [lo, lt) < pivot, [lt, i) == pivot, (gt, hi] > pivot.
            int lt = lo, i = lo, gt = hi;
            while (i <= gt) {
                int c = cmp.compare(a[i], pivot);
                if (c < 0) swap(a, lt++, i++);
                else if (c > 0) swap(a, i, gt--);
                else i++;
            }
            if (target < lt) hi = lt - 1;
            else if (target > gt) lo = gt + 1;
            else return;  // target falls inside the block equal to the pivot
        }
    }

    /** The k-th smallest value (0-based). The input array is not modified. */
    public double kthSmallest(double[] values, int k) {
        Objects.requireNonNull(values, "values");
        if (k < 0 || k >= values.length) {
            throw new IllegalArgumentException("k must be in [0, " + values.length + ")");
        }
        return selectInPlace(values.clone(), 0, values.length - 1, k);
    }

    /**
     * Rearranges {@code a[lo..hi]} in place so that {@code a[k]} holds the value a full sort
     * of that range would put there, with nothing larger before it and nothing smaller after
     * it, and returns that value. Expected O(hi - lo) time.
     *
     * <p>Because the range is left partitioned around {@code k}, a caller needing several
     * ranks can select the largest first and then search only {@code a[lo..k]} for the next.
     */
    public double selectInPlace(double[] a, int lo, int hi, int k) {
        Objects.requireNonNull(a, "a");
        if (lo < 0 || hi >= a.length || lo > hi || k < lo || k > hi) {
            throw new IllegalArgumentException("need 0 <= lo <= k <= hi < " + a.length);
        }
        while (lo < hi) {
            double pivot = a[lo + rng.nextInt(hi - lo + 1)];
            // Invariant: [lo, lt) < pivot, [lt, i) == pivot, (gt, hi] > pivot.
            int lt = lo, i = lo, gt = hi;
            while (i <= gt) {
                int c = Double.compare(a[i], pivot);
                if (c < 0) swap(a, lt++, i++);
                else if (c > 0) swap(a, i, gt--);
                else i++;
            }
            if (k < lt) hi = lt - 1;
            else if (k > gt) lo = gt + 1;
            else return pivot;
        }
        return a[k];
    }

    /** Nearest-rank percentile, {@code q} in (0, 1]. */
    public double percentile(double[] values, double q) {
        Objects.requireNonNull(values, "values");
        if (values.length == 0) throw new IllegalArgumentException("values must not be empty");
        if (!(q > 0 && q <= 1)) throw new IllegalArgumentException("q must be in (0, 1]");
        int rank = (int) Math.ceil(q * values.length) - 1;
        return kthSmallest(values, Math.max(0, rank));
    }

    private static <T> void swap(T[] a, int i, int j) {
        T t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    private static void swap(double[] a, int i, int j) {
        double t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
