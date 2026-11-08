package dev.notch.engine.text;

import java.util.Arrays;
import java.util.Objects;

/**
 * Suffix array built by prefix doubling with counting sort.
 *
 * <p>Construction is O(n log n) time and O(n) extra space. A pattern of length m is
 * located with two binary searches in O(m log n).
 *
 * <p>The text must not contain the NUL character ({@code '\0'}); it is used internally
 * as a sentinel that sorts below every other character.
 */
public final class SuffixArray {

    private static final char SENTINEL = '\0';
    private static final int ALPHABET = Character.MAX_VALUE + 1;

    private final String text;
    private final int[] sa;

    private SuffixArray(String text, int[] sa) {
        this.text = text;
        this.sa = sa;
    }

    /** Builds the suffix array of {@code text}. */
    public static SuffixArray build(String text) {
        Objects.requireNonNull(text, "text");
        if (text.indexOf(SENTINEL) >= 0) {
            throw new IllegalArgumentException("text must not contain the NUL character");
        }
        if (text.isEmpty()) {
            return new SuffixArray(text, new int[0]);
        }
        int[] withSentinel = sortCyclicShifts(text + SENTINEL);
        // withSentinel[0] is the sentinel's own position; drop it.
        int[] sa = new int[text.length()];
        System.arraycopy(withSentinel, 1, sa, 0, sa.length);
        return new SuffixArray(text, sa);
    }

    /**
     * Sorts the cyclic shifts of {@code s}. Because the last character is a unique minimum,
     * the order of cyclic shifts equals the order of suffixes.
     */
    private static int[] sortCyclicShifts(String s) {
        int n = s.length();
        int[] p = new int[n];      // shifts in sorted order
        int[] c = new int[n];      // equivalence class of each shift
        int[] cnt = new int[Math.max(ALPHABET, n)];

        for (int i = 0; i < n; i++) cnt[s.charAt(i)]++;
        for (int i = 1; i < ALPHABET; i++) cnt[i] += cnt[i - 1];
        for (int i = n - 1; i >= 0; i--) p[--cnt[s.charAt(i)]] = i;

        int classes = 1;
        c[p[0]] = 0;
        for (int i = 1; i < n; i++) {
            if (s.charAt(p[i]) != s.charAt(p[i - 1])) classes++;
            c[p[i]] = classes - 1;
        }

        int[] pn = new int[n];
        int[] cn = new int[n];
        for (int len = 1; len < n && classes < n; len <<= 1) {
            // Shifts already sorted by their second half are p[i] - len. A stable counting
            // sort by first-half class then yields the order for length 2 * len.
            for (int i = 0; i < n; i++) {
                int shifted = p[i] - len;
                pn[i] = shifted < 0 ? shifted + n : shifted;
            }
            Arrays.fill(cnt, 0, classes, 0);
            for (int i = 0; i < n; i++) cnt[c[pn[i]]]++;
            for (int i = 1; i < classes; i++) cnt[i] += cnt[i - 1];
            for (int i = n - 1; i >= 0; i--) p[--cnt[c[pn[i]]]] = pn[i];

            cn[p[0]] = 0;
            classes = 1;
            for (int i = 1; i < n; i++) {
                int cur = p[i];
                int prev = p[i - 1];
                int curSecond = c[(cur + len) % n];
                int prevSecond = c[(prev + len) % n];
                if (c[cur] != c[prev] || curSecond != prevSecond) classes++;
                cn[cur] = classes - 1;
            }
            int[] swap = c;
            c = cn;
            cn = swap;
        }
        return p;
    }

    /** The half-open range {@code [from, to)} of slots whose suffix starts with {@code pattern}. */
    public Range find(CharSequence pattern) {
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.length() == 0) {
            throw new IllegalArgumentException("pattern must not be empty");
        }
        int from = lowerBound(pattern);
        int to = upperBound(pattern, from);
        return new Range(from, to);
    }

    /** Text positions where {@code pattern} occurs, in suffix order (not text order). */
    public int[] occurrences(CharSequence pattern) {
        Range r = find(pattern);
        int[] out = new int[r.size()];
        System.arraycopy(sa, r.from(), out, 0, out.length);
        return out;
    }

    /** Number of occurrences of {@code pattern}. */
    public int count(CharSequence pattern) {
        return find(pattern).size();
    }

    private int lowerBound(CharSequence pattern) {
        int lo = 0;
        int hi = sa.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (comparePrefix(sa[mid], pattern) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private int upperBound(CharSequence pattern, int start) {
        int lo = start;
        int hi = sa.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (comparePrefix(sa[mid], pattern) <= 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /**
     * Compares the suffix at {@code start}, truncated to the pattern's length, with the
     * pattern. Returns 0 when the suffix starts with the pattern.
     */
    private int comparePrefix(int start, CharSequence pattern) {
        int m = pattern.length();
        int n = text.length();
        for (int j = 0; j < m; j++) {
            if (start + j >= n) return -1;  // suffix is a proper prefix of the pattern
            int diff = text.charAt(start + j) - pattern.charAt(j);
            if (diff != 0) return diff;
        }
        return 0;
    }

    /** A copy of the suffix array. */
    public int[] toArray() {
        return sa.clone();
    }

    public int length() {
        return sa.length;
    }

    public String text() {
        return text;
    }

    /** Half-open range of suffix-array slots. */
    public record Range(int from, int to) {
        public int size() {
            return to - from;
        }

        public boolean isEmpty() {
            return from >= to;
        }
    }
}
