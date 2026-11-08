package dev.notch.engine.probabilistic;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A Bloom filter: a bit array answering "definitely not present" or "possibly present".
 *
 * <p>Notch uses it to pre-check imported chunks for duplicates before paying to embed them.
 * A negative answer is always correct. A positive answer may be false, so callers must
 * confirm it with an exact lookup; this class never claims certainty.
 *
 * <p>Each item sets k bits chosen by double hashing, {@code g_i(x) = h1(x) + i * h2(x) mod m}
 * (Kirsch and Mitzenmacher), which needs only two hash evaluations per item. Items cannot be
 * removed; after deletions the filter is rebuilt from the authoritative store.
 */
public final class BloomFilter {

    private static final double LN2 = Math.log(2);
    private static final long SEED_1 = 0xcbf29ce484222325L;  // FNV-1a offset basis
    private static final long SEED_2 = 0x84222325cbf29ce4L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final long[] words;
    private final int m;   // bits
    private final int k;   // hash functions
    private long inserted;

    /** A filter with exactly {@code m} bits and {@code k} hash functions. */
    public BloomFilter(int m, int k) {
        if (m <= 0) throw new IllegalArgumentException("m must be positive");
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        this.m = m;
        this.k = k;
        this.words = new long[(int) ((m + 63L) / 64)];
    }

    /**
     * A filter sized for {@code expectedItems} at false-positive probability {@code fpp}:
     * {@code m = -n ln p / (ln 2)^2} and {@code k = (m / n) ln 2}.
     */
    public static BloomFilter create(long expectedItems, double fpp) {
        if (expectedItems <= 0) throw new IllegalArgumentException("expectedItems must be positive");
        if (!(fpp > 0 && fpp < 1)) throw new IllegalArgumentException("fpp must be in (0, 1)");
        long m = optimalBits(expectedItems, fpp);
        if (m > Integer.MAX_VALUE) throw new IllegalArgumentException("filter too large");
        return new BloomFilter((int) m, optimalHashes(expectedItems, m));
    }

    public static long optimalBits(long n, double p) {
        return (long) Math.ceil(-n * Math.log(p) / (LN2 * LN2));
    }

    public static int optimalHashes(long n, long m) {
        return Math.max(1, (int) Math.round((double) m / n * LN2));
    }

    /** {@code (1 - e^(-k n / m))^k}: the expected false-positive rate after n insertions. */
    public static double theoreticalFpp(long m, int k, long n) {
        return Math.pow(1 - Math.exp(-(double) k * n / m), k);
    }

    public void add(String item) {
        add(bytes(item));
    }

    public void add(byte[] item) {
        Objects.requireNonNull(item, "item");
        long h1 = hash(item, SEED_1);
        long h2 = hash(item, SEED_2) | 1;  // odd, so the k probes differ
        for (int i = 0; i < k; i++) {
            int bit = (int) Math.floorMod(h1 + i * h2, (long) m);
            words[bit >>> 6] |= 1L << (bit & 63);
        }
        inserted++;
    }

    /** False means definitely absent. True means possibly present: confirm before acting. */
    public boolean mightContain(String item) {
        return mightContain(bytes(item));
    }

    public boolean mightContain(byte[] item) {
        Objects.requireNonNull(item, "item");
        long h1 = hash(item, SEED_1);
        long h2 = hash(item, SEED_2) | 1;
        for (int i = 0; i < k; i++) {
            int bit = (int) Math.floorMod(h1 + i * h2, (long) m);
            if ((words[bit >>> 6] & (1L << (bit & 63))) == 0) return false;
        }
        return true;
    }

    /** Expected false-positive rate given the insertions so far. */
    public double expectedFpp() {
        return theoreticalFpp(m, k, inserted);
    }

    /** Bits currently set. */
    public int bitCount() {
        int c = 0;
        for (long w : words) c += Long.bitCount(w);
        return c;
    }

    public void clear() {
        java.util.Arrays.fill(words, 0);
        inserted = 0;
    }

    public int bits() {
        return m;
    }

    public int hashes() {
        return k;
    }

    public long insertions() {
        return inserted;
    }

    /** Memory used by the bit array, in bytes. */
    public long memoryBytes() {
        return words.length * 8L;
    }

    private static byte[] bytes(String s) {
        Objects.requireNonNull(s, "item");
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** FNV-1a over the bytes, then the SplitMix64 finalizer to spread the bits. */
    static long hash(byte[] data, long seed) {
        long h = seed;
        for (byte b : data) {
            h ^= (b & 0xff);
            h *= FNV_PRIME;
        }
        h ^= data.length;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }
}
