package dev.notch.api.review.core;

import dev.notch.api.explain.core.CodeDecisionLinks;
import dev.notch.api.review.ReviewReport;
import dev.notch.api.review.ReviewReport.Area;
import dev.notch.api.review.ReviewReport.Issue;
import dev.notch.api.review.ReviewReport.Recommendation;
import dev.notch.explain.CodeMap;
import dev.notch.explain.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notch's own checks, which need no model: code that goes against a recorded decision, secrets
 * in the code, committed .env files, very large files, missing tests, files nothing uses, and
 * leftover TODOs. They run on every review, so a review always has findings that are certain,
 * and when no model is available (offline, or a private project) they make the whole report.
 */
public final class ReviewRules {

    static final int LARGE_FILE_LINES = 600;
    static final int MAX_LISTED = 8;

    private static final Pattern ENV_FILE = Pattern.compile("(^|/)\\.env(\\.[\\w-]+)?$");
    private static final Pattern ENV_TEMPLATE = Pattern.compile("(?i)\\.(example|sample|template|dist)$");
    private static final Pattern TODO = Pattern.compile("\\b(TODO|FIXME|HACK|XXX)\\b");

    private ReviewRules() {
    }

    /**
     * @param contents file contents by path, when the code is at hand (a zip); empty for a review
     *                 built from the code map alone, which skips the checks that read code
     */
    public static List<Issue> findings(CodeMap map, Map<String, String> contents, List<CodeDecisionLinks.Decision> decisions) {
        List<Issue> out = new ArrayList<>();

        for (CodeDecisionLinks.Conflict c : CodeDecisionLinks.conflicts(map, decisions)) {
            out.add(new Issue("high", "decision", c.path() + " uses " + c.tool() + ", which a decision ruled out", c.path(), null,
                    "The project decided: “" + c.decisionTitle() + "”. This file imports " + c.tool()
                            + " anyway, so the code and the decision disagree.",
                    "Switch this code to what was decided, or, if the decision no longer holds, replace it in the Ledger "
                            + "so everyone works from the new one.", "rules"));
        }

        for (String path : map.stats().filesWithSecrets()) {
            CodeMap.FileInfo f = map.file(path);
            Integer line = firstSecretLine(contents.get(path));
            out.add(new Issue("high", "security", "Possible secret in " + path, path, line,
                    "This file contains something that looks like a key, token or password"
                            + (f != null && f.secrets() > 1 ? " (" + f.secrets() + " places)" : "")
                            + ". Anyone with the code can read it, and it stays in the git history.",
                    "Move it to an environment variable or a secrets manager, rotate the exposed value, and add the "
                            + "file pattern to .gitignore if it holds configuration.", "rules"));
        }

        for (CodeMap.FileInfo f : map.files()) {
            if (ENV_FILE.matcher(f.path()).find() && !ENV_TEMPLATE.matcher(f.path()).find()
                    && !map.stats().filesWithSecrets().contains(f.path())) {
                out.add(new Issue("medium", "security", f.path() + " is part of the code", f.path(), null,
                        ".env files usually hold local settings and secrets, and shouldn't be shared with the code.",
                        "Add .env to .gitignore and commit a .env.example with placeholder values instead.", "rules"));
            }
        }

        List<CodeMap.FileInfo> large = map.files().stream()
                .filter(f -> isCode(f) && f.lines() > LARGE_FILE_LINES)
                .sorted(Comparator.comparingInt(CodeMap.FileInfo::lines).reversed())
                .limit(5).toList();
        for (CodeMap.FileInfo f : large) {
            out.add(new Issue("medium", "maintainability", f.path() + " is " + String.format(Locale.ENGLISH, "%,d", f.lines())
                    + " lines long", f.path(), null,
                    "Very long files are hard to read, review and test, and changes in them conflict more often.",
                    "Split it by responsibility into smaller modules.", "rules"));
        }

        long code = map.files().stream().filter(ReviewRules::isCode).count();
        long tests = map.files().stream().filter(f -> f.role() == Role.TEST).count();
        if (tests == 0 && code >= 5) {
            out.add(new Issue("medium", "testing", "No automated tests", null, null,
                    "None of the " + code + " code files are tests, so every change has to be checked by hand and "
                            + "regressions go unnoticed.",
                    "Start with tests for the code most of the rest depends on (see Explain's “Start here”) "
                            + "and run them in CI.", "rules"));
        }

        List<String> unused = map.possiblyUnused();
        if (!unused.isEmpty()) {
            out.add(new Issue("low", "maintainability", unused.size() == 1 ? "1 file looks unused" : unused.size() + " files look unused",
                    unused.size() == 1 ? unused.get(0) : null, null,
                    "Nothing in the codebase imports " + listed(unused) + ", and the framework doesn't load "
                            + (unused.size() == 1 ? "it" : "them") + " by convention.",
                    "Check whether " + (unused.size() == 1 ? "it's" : "they're") + " still needed and delete what isn't.", "rules"));
        }

        if (!contents.isEmpty()) {
            Map<String, Integer> todos = new LinkedHashMap<>();
            int total = 0;
            for (CodeMap.FileInfo f : map.files()) {
                String text = contents.get(f.path());
                if (text == null || !isCode(f)) continue;
                int n = 0;
                Matcher m = TODO.matcher(text);
                while (m.find()) n++;
                if (n > 0) todos.put(f.path(), n);
                total += n;
            }
            if (total >= 3) {
                List<String> worst = todos.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .map(Map.Entry::getKey).toList();
                out.add(new Issue("low", "maintainability", total + " TODO or FIXME notes in the code", null, null,
                        "Unfinished work is marked in " + listed(worst) + ".",
                        "Turn the ones that matter into tasks, and finish or remove the rest.", "rules"));
            }
        }

        boolean readme = map.files().stream().anyMatch(f -> f.path().toLowerCase(Locale.ROOT).matches("readme(\\.\\w+)?"));
        if (!readme && code >= 5) {
            out.add(new Issue("low", "other", "No README", null, null,
                    "There's no README at the root, so a newcomer has no explanation of what this is or how to run it.",
                    "Add a README with what the project does, how to run it, and how to run the tests.", "rules"));
        }
        return out;
    }

    /** A report from the checks alone, for when no model reviewed the code. */
    public static ReviewReport offlineReport(String label, CodeMap map, List<Issue> issues, int decisions,
                                             ReviewReport.Coverage coverage) {
        CodeMap.Stats s = map.stats();
        List<String> languages = s.languages().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3).map(Map.Entry::getKey).toList();
        long high = issues.stream().filter(i -> "high".equals(i.severity())).count();
        StringBuilder summary = new StringBuilder(label).append(" has ").append(String.format(Locale.ENGLISH, "%,d", s.codeFiles()))
                .append(" code files (").append(String.format(Locale.ENGLISH, "%,d", s.lines())).append(" lines")
                .append(languages.isEmpty() ? "" : ", mostly " + String.join(", ", languages)).append(") in ")
                .append(map.modules().size()).append(map.modules().size() == 1 ? " module. " : " modules. ");
        if (issues.isEmpty()) summary.append("Notch's automated checks found nothing to fix.");
        else summary.append("Notch's automated checks found ").append(issues.size()).append(issues.size() == 1 ? " issue" : " issues")
                .append(high > 0 ? ", " + high + " of them serious" : "").append('.');
        summary.append(" This review covers decisions, secrets, structure and tests; a model review also reads the code for bugs.");

        List<Area> areas = new ArrayList<>();
        for (CodeMap.Module m : map.modules().stream().sorted(Comparator.comparingInt(CodeMap.Module::lines).reversed()).limit(6).toList()) {
            long found = issues.stream().filter(i -> i.file() != null && (i.file() + "/").startsWith(m.path().isEmpty() ? "" : m.path() + "/")).count();
            long serious = issues.stream().filter(i -> "high".equals(i.severity()) && i.file() != null
                    && (i.file() + "/").startsWith(m.path().isEmpty() ? "" : m.path() + "/")).count();
            String rating = serious > 0 ? "weak" : found > 0 ? "fair" : "good";
            String name = m.path().isEmpty() ? "(root)" : m.path();
            areas.add(new Area(name, rating, m.files() + " files, " + String.format(Locale.ENGLISH, "%,d", m.lines()) + " lines"
                    + (m.usedBy().isEmpty() ? "" : "; used by " + String.join(", ", m.usedBy().stream().limit(3).toList()))
                    + (found == 0 ? ". No issues found." : ". " + found + (found == 1 ? " issue." : " issues."))));
        }

        List<String> strengths = new ArrayList<>();
        long tests = map.files().stream().filter(f -> f.role() == Role.TEST).count();
        if (tests > 0) strengths.add("Has automated tests (" + tests + (tests == 1 ? " file)." : " files)."));
        if (s.filesWithSecrets().isEmpty()) strengths.add("No secrets found in the code.");
        if (decisions > 0 && issues.stream().noneMatch(i -> "decision".equals(i.category()))) {
            strengths.add("Follows all " + decisions + (decisions == 1 ? " recorded decision." : " recorded decisions."));
        }
        if (!map.entryPoints().isEmpty()) strengths.add("Clear entry points (" + map.entryPoints().size() + ").");

        return new ReviewReport(summary.toString(), areas, strengths, issues, recommendationsFor(issues), coverage);
    }

    /** One recommendation per kind of problem, most urgent first. */
    public static List<Recommendation> recommendationsFor(List<Issue> issues) {
        List<Recommendation> out = new ArrayList<>();
        if (has(issues, "security")) out.add(new Recommendation("now", "Remove secrets from the code",
                "Rotate any exposed keys, load them from environment variables, and keep .env files out of git."));
        if (has(issues, "decision")) out.add(new Recommendation("now", "Bring the code back in line with your decisions",
                "Fix the files that go against a recorded decision, or replace the decision if the project has moved on."));
        if (has(issues, "bug")) out.add(new Recommendation("now", "Fix the bugs found",
                "Start with the high-severity ones; each issue names the file and line."));
        if (has(issues, "testing")) out.add(new Recommendation("soon", "Add tests for the core code",
                "Cover the most-used modules first and run the tests on every push."));
        if (has(issues, "performance")) out.add(new Recommendation("soon", "Address the performance issues",
                "They're listed with the files they're in."));
        if (has(issues, "maintainability")) out.add(new Recommendation("later", "Tidy up the structure",
                "Split the largest files, delete unused ones, and turn TODOs into tasks."));
        return out;
    }

    static boolean isCode(CodeMap.FileInfo f) {
        return switch (f.role()) {
            case TEST, CONFIG, MIGRATION, STYLE, DOC, DATA -> false;
            default -> true;
        };
    }

    private static boolean has(List<Issue> issues, String category) {
        return issues.stream().anyMatch(i -> category.equals(i.category()));
    }

    private static String listed(List<String> paths) {
        List<String> shown = paths.subList(0, Math.min(MAX_LISTED, paths.size()));
        String s = String.join(", ", shown);
        return paths.size() > shown.size() ? s + " and " + (paths.size() - shown.size()) + " more" : s;
    }

    private static Integer firstSecretLine(String text) {
        if (text == null) return null;
        var found = new dev.notch.engine.text.SecretScanner().scan(text).findings();
        if (found.isEmpty()) return null;
        int at = found.get(0).start();
        int line = 1;
        for (int i = 0; i < at && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
