package dev.notch.engine.text;

import java.util.Arrays;
import java.util.Objects;

/**
 * Knuth–Morris–Pratt string matching: O(n + m) time, O(m) extra space, no backtracking
 * over the text. Matches may overlap.
 */
public final class Kmp {

    private Kmp() {
    }

    /**
     * The prefix function: {@code pi[i]} is the length of the longest proper prefix of
     * {@code pattern[0..i]} that is also a suffix of it.
     */
    public static int[] prefixFunction(CharSequence pattern) {
        Objects.requireNonNull(pattern, "pattern");
        int m = pattern.length();
        int[] pi = new int[m];
        for (int i = 1; i < m; i++) {
            int k = pi[i - 1];
            while (k > 0 && pattern.charAt(i) != pattern.charAt(k)) k = pi[k - 1];
            if (pattern.charAt(i) == pattern.charAt(k)) k++;
            pi[i] = k;
        }
        return pi;
    }

    /** All start positions of {@code pattern} in {@code text}, ascending. */
    public static int[] findAll(CharSequence text, CharSequence pattern) {
        return findAll(text, pattern, false);
    }

    public static int[] findAll(CharSequence text, CharSequence pattern, boolean ignoreCase) {
        return search(text, pattern, ignoreCase, 0, Integer.MAX_VALUE);
    }

    /** First position of {@code pattern} at or after {@code from}, or -1. */
    public static int indexOf(CharSequence text, CharSequence pattern, int from) {
        int[] hits = search(text, pattern, false, from, 1);
        return hits.length == 0 ? -1 : hits[0];
    }

    private static int[] search(CharSequence text, CharSequence pattern, boolean ignoreCase,
                                int from, int limit) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(pattern, "pattern");
        int m = pattern.length();
        if (m == 0) throw new IllegalArgumentException("pattern must not be empty");
        if (from < 0) throw new IllegalArgumentException("from must be >= 0");

        CharSequence p = ignoreCase ? lower(pattern) : pattern;
        int[] pi = prefixFunction(p);
        int[] hits = new int[8];
        int found = 0;
        int k = 0;  // characters of the pattern currently matched
        for (int i = from; i < text.length(); i++) {
            char c = ignoreCase ? Character.toLowerCase(text.charAt(i)) : text.charAt(i);
            while (k > 0 && c != p.charAt(k)) k = pi[k - 1];
            if (c == p.charAt(k)) k++;
            if (k == m) {
                if (found == hits.length) hits = Arrays.copyOf(hits, found * 2);
                hits[found++] = i - m + 1;
                if (found >= limit) break;
                k = pi[k - 1];
            }
        }
        return Arrays.copyOf(hits, found);
    }

    private static String lower(CharSequence s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) sb.append(Character.toLowerCase(s.charAt(i)));
        return sb.toString();
    }
}
