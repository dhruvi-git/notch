package dev.notch.engine.score;

import dev.notch.engine.select.RandomizedSelect;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * The score cut-offs behind badges such as "Top 10% in SQL", found with randomized
 * Quickselect instead of sorting every candidate.
 *
 * <p>For each band (top 1%, 5%, 10%, 25%) the cut-off is the nearest-rank percentile
 * (99th, 95th, 90th, 75th) of all scores for the skill. One copy of the scores is made; each
 * selection then searches only the part at or below the previous cut-off, so all four together
 * take expected O(n) time, against O(n log n) for a sort. A score gets the tightest band whose cut-off it reaches.
 *
 * <p>Two rules keep badges honest. Bands are only given when at least {@link #MIN_PEERS}
 * candidates have the skill, since "top 1%" of a handful of people means nothing. And a cut-off
 * equal to the lowest score is dropped: when most scores tie at the bottom, reaching that
 * cut-off does not set anyone apart.
 */
public final class PercentileBands {

    /** Band sizes in percent, tightest first. */
    public static final int[] BANDS = {1, 5, 10, 25};
    public static final int MIN_PEERS = 20;

    private final double[] cutoffs; // aligned with BANDS; NaN when the band is not given
    private final int peers;

    private PercentileBands(double[] cutoffs, int peers) {
        this.cutoffs = cutoffs;
        this.peers = peers;
    }

    /** Cut-offs for one skill's scores. {@code select} supplies the randomness (seed it in tests). */
    public static PercentileBands of(double[] scores, RandomizedSelect select) {
        Objects.requireNonNull(scores, "scores");
        Objects.requireNonNull(select, "select");
        for (double s : scores) {
            if (!(s >= 0 && s <= 100)) throw new IllegalArgumentException("score out of [0, 100]: " + s);
        }
        double[] cut = new double[BANDS.length];
        java.util.Arrays.fill(cut, Double.NaN);
        if (scores.length < MIN_PEERS) return new PercentileBands(cut, scores.length);

        double min = Double.POSITIVE_INFINITY;
        for (double s : scores) min = Math.min(min, s);
        // One working copy. Bands run tightest first, so ranks fall: each selection leaves
        // a[0..k] holding everything at or below the cut-off, and the next one searches only that.
        double[] a = scores.clone();
        int hi = a.length - 1;
        for (int b = 0; b < BANDS.length; b++) {
            int k = Math.max(0, (int) Math.ceil((100 - BANDS[b]) / 100.0 * a.length) - 1);
            double c = select.selectInPlace(a, 0, hi, k);
            cut[b] = c > min ? c : Double.NaN;
            hi = k;
        }
        return new PercentileBands(cut, scores.length);
    }

    /** The tightest band (1, 5, 10 or 25) the score reaches, or empty if none. */
    public OptionalInt bandOf(double score) {
        for (int b = 0; b < BANDS.length; b++) {
            if (!Double.isNaN(cutoffs[b]) && score >= cutoffs[b]) return OptionalInt.of(BANDS[b]);
        }
        return OptionalInt.empty();
    }

    /** The cut-off for a band, or NaN when that band is not given. */
    public double cutoff(int bandPercent) {
        for (int b = 0; b < BANDS.length; b++) if (BANDS[b] == bandPercent) return cutoffs[b];
        throw new IllegalArgumentException("no such band: " + bandPercent);
    }

    public int peers() {
        return peers;
    }
}
