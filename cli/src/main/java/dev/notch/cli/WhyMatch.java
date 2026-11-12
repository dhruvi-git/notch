package dev.notch.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a search result really answers "have I seen this before?". Search always
 * returns its best passages, related or not; this keeps only those containing what's
 * distinctive about the query (error codes, identifiers, paths, long words), or most of its
 * words when nothing stands out.
 */
final class WhyMatch {

    private static final Set<String> COMMON = Set.of("error", "errors", "failed", "cannot", "could", "should", "would",
            "there", "their", "which", "where", "while", "about", "after", "before", "these", "those", "using", "inside",
            "being", "found", "every", "because", "something", "nothing");

    private WhyMatch() {
    }

    static boolean relevant(String query, String text) {
        if (text == null) return false;
        String hay = text.toLowerCase(Locale.ROOT);
        List<String> distinctive = new ArrayList<>();
        List<String> words = new ArrayList<>();
        for (String raw : query.split("[\\s\"'`()\\[\\]{},;]+")) {
            String w = raw.replaceAll("^[.:]+|[.:]+$", "");
            if (w.length() < 3) continue;
            String lower = w.toLowerCase(Locale.ROOT);
            if (COMMON.contains(lower)) continue;
            words.add(lower);
            if (isDistinctive(w)) distinctive.add(lower);
        }
        if (words.isEmpty()) return false;
        if (!distinctive.isEmpty()) {
            for (String d : distinctive) if (hay.contains(d)) return true;
            return false;
        }
        int found = 0;
        for (String w : words) if (hay.contains(w)) found++;
        return found * 2 >= words.size() && found >= Math.min(2, words.size());
    }

    /** Error codes, identifiers and paths: uppercase runs, inner capitals, digits, or code punctuation; or long words. */
    static boolean isDistinctive(String w) {
        boolean upperRun = w.length() >= 4 && w.equals(w.toUpperCase(Locale.ROOT)) && w.chars().anyMatch(Character::isLetter);
        boolean innerCapital = w.length() > 1 && !w.substring(1).equals(w.substring(1).toLowerCase(Locale.ROOT));
        boolean code = w.chars().anyMatch(Character::isDigit) || w.contains("_") || w.contains(".") || w.contains("/") || w.contains(":");
        return upperRun || innerCapital || code || w.length() >= 9;
    }
}
