package dev.notch.api.ideation.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Scores a decision matrix and checks how much the result depends on the weights.
 *
 * <p><b>Score:</b> for each option, the weighted sum of its scores, as a percentage of the best
 * possible (every criterion scored 5).
 *
 * <p><b>Robustness (Monte Carlo):</b> the weights are opinions, so the comparison is re-run
 * {@value #SAMPLES} times with each weight multiplied by an independent random factor between
 * 0.5 and 1.5, and the share of runs each option wins is reported. Ties split the win. The
 * random generator is seeded from the matrix itself, so the same matrix always reports the same
 * numbers. Cost: O(samples × criteria × options).
 *
 * <p><b>What decides it:</b> per criterion, the weighted score difference between the leader and
 * the runner-up; the positive ones are where the lead comes from.
 */
public final class ComparisonAnalyzer {

    public static final int SAMPLES = 2000;
    static final double SPREAD = 0.5;

    public Analysis analyze(Comparison c) {
        List<String> problems = validate(c);
        int unscored = 0;
        for (Comparison.Criterion cr : c.criteria()) {
            for (Comparison.Option o : c.options()) if (c.score(cr.id(), o.id()) == null) unscored++;
        }
        if (!problems.isEmpty() || c.criteria().isEmpty() || c.options().isEmpty()) {
            return new Analysis(List.of(), List.of(), List.of(), 0, List.of(), unscored, problems);
        }

        double[] weights = c.criteria().stream().mapToDouble(Comparison.Criterion::weight).toArray();
        double[] totals = totals(c, weights);
        double best = 0;
        for (double w : weights) best += w * Comparison.MAX_SCORE;

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < c.options().size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> -totals[i]).thenComparing(i -> c.options().get(i).name()));
        List<Analysis.Standing> standings = new ArrayList<>();
        int rank = 0;
        double previous = Double.NaN;
        for (int pos = 0; pos < order.size(); pos++) {
            int i = order.get(pos);
            if (totals[i] != previous) rank = pos + 1;
            previous = totals[i];
            standings.add(new Analysis.Standing(c.options().get(i).id(), c.options().get(i).name(),
                    round1(100 * totals[i] / best), rank));
        }
        List<String> winners = standings.stream().filter(s -> s.rank() == 1).map(Analysis.Standing::optionId).toList();

        return new Analysis(standings, winners, robustness(c, weights), SAMPLES,
                decisive(c, order), unscored, List.of());
    }

    private static double[] totals(Comparison c, double[] weights) {
        double[] t = new double[c.options().size()];
        for (int k = 0; k < c.criteria().size(); k++) {
            String crit = c.criteria().get(k).id();
            for (int i = 0; i < t.length; i++) {
                Integer s = c.score(crit, c.options().get(i).id());
                if (s != null) t[i] += weights[k] * s;
            }
        }
        return t;
    }

    private List<Analysis.Share> robustness(Comparison c, double[] weights) {
        Random rnd = new Random(seed(c));
        double[] wins = new double[c.options().size()];
        double[] w = new double[weights.length];
        for (int sample = 0; sample < SAMPLES; sample++) {
            for (int k = 0; k < w.length; k++) w[k] = weights[k] * (1 - SPREAD + 2 * SPREAD * rnd.nextDouble());
            double[] t = totals(c, w);
            double top = Double.NEGATIVE_INFINITY;
            for (double v : t) top = Math.max(top, v);
            int leaders = 0;
            for (double v : t) if (v >= top - 1e-9) leaders++;
            for (int i = 0; i < t.length; i++) if (t[i] >= top - 1e-9) wins[i] += 1.0 / leaders;
        }
        List<Analysis.Share> out = new ArrayList<>();
        for (int i = 0; i < wins.length; i++) {
            out.add(new Analysis.Share(c.options().get(i).id(), c.options().get(i).name(), round3(wins[i] / SAMPLES)));
        }
        out.sort(Comparator.comparingDouble((Analysis.Share s) -> -s.share()).thenComparing(Analysis.Share::name));
        return out;
    }

    private static List<Analysis.Driver> decisive(Comparison c, List<Integer> order) {
        if (order.size() < 2) return List.of();
        String lead = c.options().get(order.get(0)).id();
        String next = c.options().get(order.get(1)).id();
        List<Analysis.Driver> out = new ArrayList<>();
        for (Comparison.Criterion cr : c.criteria()) {
            Integer a = c.score(cr.id(), lead);
            Integer b = c.score(cr.id(), next);
            double gap = cr.weight() * ((a == null ? 0 : a) - (b == null ? 0 : b));
            if (gap > 0) out.add(new Analysis.Driver(cr.id(), cr.name(), gap));
        }
        out.sort(Comparator.comparingDouble((Analysis.Driver d) -> -d.points()).thenComparing(Analysis.Driver::name));
        return out;
    }

    /** Problems that make the comparison meaningless, in plain words. Empty means it's valid. */
    public static List<String> validate(Comparison c) {
        List<String> p = new ArrayList<>();
        if (c.question().isEmpty()) p.add("Write the question you're deciding.");
        if (c.question().length() > 200) p.add("Keep the question under 200 characters.");
        if (c.options().size() < Comparison.MIN_OPTIONS) p.add("Add at least two options to compare.");
        if (c.options().size() > Comparison.MAX_OPTIONS) p.add("Compare at most " + Comparison.MAX_OPTIONS + " options at once.");
        if (c.criteria().isEmpty()) p.add("Add at least one criterion to judge the options by.");
        if (c.criteria().size() > Comparison.MAX_CRITERIA) p.add("Use at most " + Comparison.MAX_CRITERIA + " criteria.");
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Comparison.Option o : c.options()) {
            if (o.id() == null || o.id().isBlank() || !ids.add(o.id())) p.add("Each option needs its own id.");
            if (o.name() == null || o.name().isBlank()) p.add("Every option needs a name.");
            else if (!names.add(o.name().strip().toLowerCase(Locale.ROOT))) p.add("Two options are both called \"" + o.name().strip() + "\".");
            else if (o.name().length() > 60) p.add("Keep option names under 60 characters.");
        }
        names.clear();
        for (Comparison.Criterion cr : c.criteria()) {
            if (cr.id() == null || cr.id().isBlank() || !ids.add(cr.id())) p.add("Each criterion needs its own id.");
            if (cr.name() == null || cr.name().isBlank()) p.add("Every criterion needs a name.");
            else if (!names.add(cr.name().strip().toLowerCase(Locale.ROOT))) p.add("Two criteria are both called \"" + cr.name().strip() + "\".");
            if (cr.weight() < Comparison.MIN_WEIGHT || cr.weight() > Comparison.MAX_WEIGHT) p.add("Weights go from 1 to 5.");
        }
        for (Map<String, Integer> row : c.scores().values()) {
            for (Integer s : row.values()) {
                if (s != null && (s < Comparison.MIN_SCORE || s > Comparison.MAX_SCORE)) {
                    p.add("Scores go from 1 to 5.");
                    return dedupe(p);
                }
            }
        }
        return dedupe(p);
    }

    /** Default criteria for a new comparison: what most technical choices come down to. */
    public static List<Comparison.Criterion> defaultCriteria() {
        return List.of(
                new Comparison.Criterion("c1", "Fits the problem", 5),
                new Comparison.Criterion("c2", "Effort to build and run", 3),
                new Comparison.Criterion("c3", "Team already knows it", 3),
                new Comparison.Criterion("c4", "Cost", 2));
    }

    private static List<String> dedupe(List<String> p) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(p));
    }

    private static long seed(Comparison c) {
        StringBuilder sb = new StringBuilder();
        for (Comparison.Option o : c.options()) sb.append(o.id()).append('|');
        for (Comparison.Criterion cr : c.criteria()) {
            sb.append(cr.id()).append(':').append(cr.weight()).append('|');
            for (Comparison.Option o : c.options()) sb.append(c.score(cr.id(), o.id())).append(',');
        }
        long h = 1125899906842597L;
        for (int i = 0; i < sb.length(); i++) h = 31 * h + sb.charAt(i);
        return h;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** Ordered map helper for building score tables in tests and the service. */
    public static Map<String, Map<String, Integer>> table() {
        return new LinkedHashMap<>();
    }
}
