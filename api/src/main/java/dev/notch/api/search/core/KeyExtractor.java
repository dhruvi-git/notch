package dev.notch.api.search.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls code-shaped keys out of text: file paths, identifiers, error names and codes.
 * They feed {@code @} autocomplete, and they link conversations into threads.
 */
public final class KeyExtractor {

    private static final Pattern PATH = Pattern.compile("(?<![\\w/.-])(?:[\\w.-]+/)+[\\w.-]+\\.[A-Za-z]{1,6}\\b");
    private static final Pattern ERROR_NAME = Pattern.compile("\\b[A-Z][A-Za-z]*(?:Error|Exception)\\b");
    private static final Pattern ERROR_CODE = Pattern.compile("\\bE[A-Z]{4,}\\b");
    private static final Pattern CAMEL = Pattern.compile("\\b[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*\\b");
    private static final Pattern PASCAL = Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\\b");
    private static final Pattern SNAKE = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");
    private static final Pattern BACKTICK = Pattern.compile("`([^`\\n]{2,60})`");

    private KeyExtractor() {
    }

    /** Keys worth suggesting in autocomplete, in order of first appearance, with repeats. */
    public static List<String> autocompleteKeys(String text) {
        List<String> out = new ArrayList<>();
        for (Pattern p : List.of(PATH, ERROR_NAME, ERROR_CODE, CAMEL, PASCAL, SNAKE)) collect(p, text, 0, out);
        collect(BACKTICK, text, 1, out);
        return out;
    }

    /**
     * Keys strong enough to say two conversations are about the same work: file paths, error
     * names and codes, and backticked identifiers. Ordinary identifiers are too common.
     */
    public static Set<String> linkKeys(String text) {
        List<String> out = new ArrayList<>();
        for (Pattern p : List.of(PATH, ERROR_NAME, ERROR_CODE)) collect(p, text, 0, out);
        collect(BACKTICK, text, 1, out);
        return new LinkedHashSet<>(out);
    }

    private static void collect(Pattern p, String text, int group, List<String> out) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String key = m.group(group).strip();
            if (key.length() >= 2 && key.length() <= 120) out.add(key);
        }
    }
}
