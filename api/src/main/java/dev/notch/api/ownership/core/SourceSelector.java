package dev.notch.api.ownership.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Picks which files of a repository to read for an Ownership Check: the candidate's own source,
 * not dependencies, build output, generated or minified code, tests or lock files. Files are
 * taken round-robin across languages, largest first, so a full-stack project gets questions on
 * both its frontend and its backend.
 */
public final class SourceSelector {

    public record Entry(String path, long size) {
    }

    public static final int DEFAULT_MAX_FILES = 20;
    static final long MIN_BYTES = 150;
    static final long MAX_BYTES = 60_000;

    private static final Pattern SKIP = Pattern.compile(
            "(^|/)(node_modules|vendor|dist|build|out|target|\\.next|\\.git|venv|\\.venv|env|site-packages|__pycache__|"
                    + "coverage|\\.idea|\\.vscode|migrations|generated|third_party|external|bower_components|public/assets)/"
                    + "|\\.min\\.(js|css)$|\\.d\\.ts$|(^|/)(test|tests|__tests__|spec|specs)/|[._-](test|spec)\\.[a-z]+$"
                    + "|(^|/)(setup|manage|conftest|jest\\.config|webpack\\.config|vite\\.config|next\\.config|babel\\.config)\\.[a-z]+$",
            Pattern.CASE_INSENSITIVE);

    private SourceSelector() {
    }

    public static List<Entry> choose(List<Entry> tree, int maxFiles) {
        if (maxFiles < 1) throw new IllegalArgumentException("maxFiles must be positive");
        Map<String, List<Entry>> byLanguage = new LinkedHashMap<>();
        tree.stream()
                .filter(e -> e.size() >= MIN_BYTES && e.size() <= MAX_BYTES)
                .filter(e -> RegionExtractor.language(e.path()) != null)
                .filter(e -> !SKIP.matcher(e.path().toLowerCase(Locale.ROOT)).find())
                .sorted(Comparator.comparingLong(Entry::size).reversed().thenComparing(Entry::path))
                .forEach(e -> byLanguage.computeIfAbsent(RegionExtractor.language(e.path()), k -> new ArrayList<>()).add(e));
        // Languages with more code first, then one file from each in turn.
        List<List<Entry>> queues = new ArrayList<>(byLanguage.values());
        queues.sort(Comparator.comparingLong((List<Entry> q) -> -q.stream().mapToLong(Entry::size).sum()));
        List<Entry> out = new ArrayList<>();
        for (int round = 0; out.size() < maxFiles; round++) {
            boolean any = false;
            for (List<Entry> q : queues) {
                if (round < q.size() && out.size() < maxFiles) {
                    out.add(q.get(round));
                    any = true;
                }
            }
            if (!any) break;
        }
        return out;
    }
}
