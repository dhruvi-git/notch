package dev.notch.api.review.core;

import dev.notch.engine.select.RandomizedSelect;
import dev.notch.engine.text.SecretScanner;
import dev.notch.explain.CodeMap;
import dev.notch.explain.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chooses the code a model reads for a review, within a size budget: the code the rest depends
 * on and the entry points first, then the most-imported files, then the rest by size. Secrets are
 * redacted before anything leaves, and each line is numbered so issues can cite it.
 */
public final class ReviewSelection {

    /** About 60K tokens of code: a thorough read that stays quick and cheap on a small model. */
    public static final int DEFAULT_BUDGET_CHARS = 220_000;
    static final int MAX_FILE_CHARS = 30_000;
    /** More than the budget can ever hold: the budget fits at most a few hundred small files. */
    static final int MAX_CANDIDATES = 400;
    private static final RandomizedSelect SELECT = new RandomizedSelect(7);

    /** A file as sent: {@code numbered} is the (redacted) code with line numbers. */
    public record Picked(String path, String numbered, int lines, boolean truncated) {
    }

    /** {@code reviewableFiles}: every file a reviewer could read, so coverage compares like with like. */
    public record Selection(List<Picked> files, int reviewableFiles, int linesSent) {
    }

    private ReviewSelection() {
    }

    public static Selection select(CodeMap map, Map<String, String> contents, int budgetChars) {
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < map.startHere().size(); i++) rank.put(map.startHere().get(i), i);
        List<CodeMap.FileInfo> reviewable = map.files().stream()
                .filter(f -> contents.containsKey(f.path()) && reviewable(f)).toList();
        Comparator<CodeMap.FileInfo> mostImportantFirst = Comparator
                .comparingInt((CodeMap.FileInfo f) -> rank.getOrDefault(f.path(), Integer.MAX_VALUE))
                .thenComparing(f -> !map.entryPoints().contains(f.path()))
                .thenComparing(f -> f.role() == Role.TEST)
                .thenComparing(Comparator.comparingInt((CodeMap.FileInfo f) -> f.importedBy().size()).reversed())
                .thenComparing(Comparator.comparingInt(CodeMap.FileInfo::lines).reversed())
                .thenComparing(CodeMap.FileInfo::path);
        // Only the files that can fit the budget matter, so take the top ones with Quickselect
        // (expected linear time) instead of sorting thousands of files to read a few dozen.
        List<CodeMap.FileInfo> candidates = reviewable.isEmpty() ? List.of()
                : SELECT.topK(reviewable, Math.min(reviewable.size(), MAX_CANDIDATES), mostImportantFirst.reversed());
        long codeFiles = reviewable.size();

        SecretScanner scanner = new SecretScanner();
        List<Picked> out = new ArrayList<>();
        int used = 0;
        int lines = 0;
        for (CodeMap.FileInfo f : candidates) {
            if (used >= budgetChars) break;
            String code = scanner.scan(contents.get(f.path())).redacted();
            boolean truncated = false;
            int room = Math.min(MAX_FILE_CHARS, budgetChars - used);
            if (code.length() > room) {
                if (room < 2_000) continue;  // too little left for a useful excerpt; a smaller file may still fit
                code = code.substring(0, code.lastIndexOf('\n', room) > 0 ? code.lastIndexOf('\n', room) : room);
                truncated = true;
            }
            String numbered = number(code);
            int n = (int) code.lines().count();
            out.add(new Picked(f.path(), numbered, n, truncated));
            used += numbered.length() + f.path().length() + 20;
            lines += n;
        }
        return new Selection(out, (int) codeFiles, lines);
    }

    /** Code and tests; configuration, data, docs and styles say little about the code's quality. */
    public static boolean reviewable(CodeMap.FileInfo f) {
        // Environment files hold settings and secrets, not code; even redacted they aren't sent.
        if (f.path().matches("(?i)(^|.*/)\\.env(\\..*)?")) return false;
        return switch (f.role()) {
            case DOC, DATA, STYLE, MIGRATION -> false;
            case CONFIG -> f.lines() <= 200;  // package.json, Dockerfile and the like, not generated config
            default -> true;
        };
    }

    static String number(String code) {
        StringBuilder sb = new StringBuilder(code.length() + code.length() / 8);
        int n = 1;
        for (String line : code.split("\n", -1)) {
            sb.append(n++).append("| ").append(line).append('\n');
        }
        return sb.toString();
    }
}
