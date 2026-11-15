package dev.notch.api.review.core;

import dev.notch.api.review.ReviewReport;
import dev.notch.api.review.ReviewReport.Area;
import dev.notch.api.review.ReviewReport.Issue;
import dev.notch.api.review.ReviewReport.Recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a review written by a model or sent by Claude Code (untrusted JSON) into a clean report:
 * known severities, categories and ratings, sizes capped, and, when the reviewed files are known,
 * issues that cite a file the reviewer never saw dropped and impossible line numbers removed. Then
 * merges in Notch's own checks, which win where both found the same thing.
 */
public final class ReviewSanitizer {

    static final int MAX_ISSUES = 60;
    static final int MAX_AREAS = 16;
    static final int MAX_STRENGTHS = 10;
    static final int MAX_RECOMMENDATIONS = 12;

    private static final Set<String> CATEGORIES = Set.of("bug", "security", "performance", "maintainability", "testing", "decision", "other");
    private static final Pattern PATH_WITH_LINE = Pattern.compile("^(.+?):(\\d+)(?::\\d+)?$");

    private ReviewSanitizer() {
    }

    /**
     * @param raw        the parsed JSON object
     * @param knownFiles line counts of the files the reviewer was given, or null to accept any path
     * @param source     "model" for Claude via the API, "agent" for Claude Code
     */
    public static ReviewReport clean(Map<String, Object> raw, Map<String, Integer> knownFiles, String source,
                                     ReviewReport.Coverage coverage) {
        String summary = clip(str(raw.get("summary")), 2_000);

        List<Area> areas = new ArrayList<>();
        for (Map<String, Object> a : objects(raw.get("areas"))) {
            String name = clip(str(a.get("name")), 120);
            String assessment = clip(str(a.get("assessment")), 600);
            if (name == null || assessment == null) continue;
            areas.add(new Area(name, rating(str(a.get("rating"))), assessment));
            if (areas.size() == MAX_AREAS) break;
        }

        List<String> strengths = new ArrayList<>();
        if (raw.get("strengths") instanceof List<?> l) {
            for (Object o : l) {
                String s = clip(str(o), 300);
                if (s != null) strengths.add(s);
                if (strengths.size() == MAX_STRENGTHS) break;
            }
        }

        List<Issue> issues = new ArrayList<>();
        for (Map<String, Object> i : objects(raw.get("issues"))) {
            String title = clip(str(i.get("title")), 200);
            if (title == null) continue;
            String file = path(str(i.get("file")));
            Integer line = i.get("line") instanceof Number n ? n.intValue() : null;
            if (file != null) {
                Matcher m = PATH_WITH_LINE.matcher(file);
                if (m.matches()) {
                    file = m.group(1);
                    if (line == null) line = Integer.parseInt(m.group(2));
                }
            }
            if (file != null && knownFiles != null) {
                Integer lines = knownFiles.get(file);
                if (lines == null) continue;  // cites a file the reviewer wasn't given: an invented finding
                if (line != null && line > lines) line = null;
            }
            if (line != null && line < 1) line = null;
            if (file == null) line = null;
            issues.add(new Issue(severity(str(i.get("severity"))), category(str(i.get("category"))), title, file, line,
                    clip(str(i.get("detail")), 1_200), clip(str(i.get("recommendation")), 800), source));
            if (issues.size() == MAX_ISSUES) break;
        }

        List<Recommendation> recommendations = new ArrayList<>();
        for (Map<String, Object> r : objects(raw.get("recommendations"))) {
            String title = clip(str(r.get("title")), 200);
            if (title == null) continue;
            recommendations.add(new Recommendation(priority(str(r.get("priority"))), title, clip(str(r.get("detail")), 800)));
            if (recommendations.size() == MAX_RECOMMENDATIONS) break;
        }
        recommendations.sort(Comparator.comparingInt(r -> List.of("now", "soon", "later").indexOf(r.priority())));
        return new ReviewReport(summary, areas, strengths, sorted(issues), recommendations, coverage);
    }

    /**
     * The reviewer's report with Notch's own findings added. For decisions and security the
     * checks are certain, so their version of a finding replaces the reviewer's; elsewhere the
     * reviewer's (usually more specific) version is kept.
     */
    public static ReviewReport merge(ReviewReport reviewer, List<Issue> rules, ReviewReport fallback) {
        Set<String> generalCategories = new HashSet<>();
        for (Issue i : reviewer.issues()) {
            if (i.file() == null) generalCategories.add(i.category());
        }
        // Rule findings by key; a codebase-wide rule finding gives way to the reviewer's own.
        Map<String, Issue> ruleByKey = new LinkedHashMap<>();
        for (Issue i : rules) {
            if (i.file() == null && generalCategories.contains(i.category())) continue;
            ruleByKey.putIfAbsent(key(i), i);
        }
        List<Issue> issues = new ArrayList<>();
        Set<String> replaced = new HashSet<>();
        for (Issue i : reviewer.issues()) {
            String k = key(i);
            Issue rule = ruleByKey.get(k);
            if (rule != null && ("decision".equals(rule.category()) || "security".equals(rule.category()))) continue;
            if (rule != null) replaced.add(k);
            issues.add(i);
        }
        for (Map.Entry<String, Issue> e : ruleByKey.entrySet()) {
            if (!replaced.contains(e.getKey())) issues.add(e.getValue());
        }
        issues = sorted(issues);
        if (issues.size() > MAX_ISSUES) issues = issues.subList(0, MAX_ISSUES);
        return new ReviewReport(
                reviewer.summary() != null ? reviewer.summary() : fallback.summary(),
                reviewer.areas().isEmpty() ? fallback.areas() : reviewer.areas(),
                reviewer.strengths().isEmpty() ? fallback.strengths() : reviewer.strengths(),
                issues,
                reviewer.recommendations().isEmpty() ? ReviewRules.recommendationsFor(issues) : reviewer.recommendations(),
                reviewer.coverage());
    }

    private static String key(Issue i) {
        return i.category() + "|" + (i.file() == null ? "*" + i.title().toLowerCase(Locale.ROOT) : i.file());
    }

    static List<Issue> sorted(List<Issue> issues) {
        List<String> severities = List.of("high", "medium", "low");
        List<String> first = List.of("decision", "security", "bug");
        List<Issue> out = new ArrayList<>(issues);
        out.sort(Comparator.comparingInt((Issue i) -> severities.indexOf(i.severity()))
                .thenComparingInt(i -> first.contains(i.category()) ? first.indexOf(i.category()) : first.size()));
        return out;
    }

    static String severity(String s) {
        String v = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "critical", "high", "major", "severe", "blocker" -> "high";
            case "low", "minor", "info", "trivial", "nit" -> "low";
            default -> "medium";
        };
    }

    static String category(String s) {
        String v = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
        if (CATEGORIES.contains(v)) return v;
        return switch (v) {
            case "bugs", "correctness", "error", "reliability" -> "bug";
            case "perf", "efficiency" -> "performance";
            case "tests", "test", "test coverage" -> "testing";
            case "style", "design", "architecture", "readability", "structure" -> "maintainability";
            case "decisions", "drift" -> "decision";
            default -> "other";
        };
    }

    static String rating(String s) {
        String v = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "good", "strong", "great", "excellent" -> "good";
            case "weak", "poor", "bad", "needs work", "needs_work" -> "weak";
            default -> "fair";
        };
    }

    static String priority(String s) {
        String v = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "now", "high", "urgent", "immediate" -> "now";
            case "later", "low", "eventually" -> "later";
            default -> "soon";
        };
    }

    static String path(String p) {
        if (p == null) return null;
        String v = p.strip().replace('\\', '/').replaceAll("^\\./", "").replaceAll("^/+", "");
        return v.isEmpty() || v.length() > 500 ? null : v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().strip();
        return s.isEmpty() ? null : s;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
