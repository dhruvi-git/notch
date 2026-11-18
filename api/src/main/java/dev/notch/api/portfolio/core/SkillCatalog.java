package dev.notch.api.portfolio.core;

import dev.notch.engine.text.RadixTree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The fixed list of skills Notch knows, with the other names people use for them.
 *
 * <p>Every name and alias is normalized ({@link #normalize}) and indexed twice: in a hash map
 * for exact lookup and text extraction, and in the engine's radix tree for autocomplete by
 * prefix. Extraction scans a text word by word and takes the longest name that matches at
 * each position, so "machine learning engineer" yields machine learning, not learning.
 */
public final class SkillCatalog {

    public record Skill(int id, String name, String domain) {
        public Skill {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(domain, "domain");
        }
    }

    private static final double NAME_WEIGHT = 2.0;
    private static final double ALIAS_WEIGHT = 1.0;

    private final Map<Integer, Skill> byId = new LinkedHashMap<>();
    private final Map<String, Integer> byKey = new HashMap<>();
    private final RadixTree tree = new RadixTree();
    private int maxWords = 1;

    private SkillCatalog() {
    }

    /**
     * @param skills  every skill, ids and names unique
     * @param aliases alternative name → skill id; an alias equal to a skill's name is ignored
     */
    public static SkillCatalog of(Collection<Skill> skills, Map<String, Integer> aliases) {
        SkillCatalog c = new SkillCatalog();
        for (Skill s : skills) {
            if (c.byId.putIfAbsent(s.id(), s) != null) throw new IllegalArgumentException("duplicate skill id " + s.id());
            c.index(s.name(), s.id(), NAME_WEIGHT);
        }
        for (Map.Entry<String, Integer> a : aliases.entrySet()) {
            if (!c.byId.containsKey(a.getValue())) throw new IllegalArgumentException("alias for unknown skill: " + a.getKey());
            c.index(a.getKey(), a.getValue(), ALIAS_WEIGHT);
        }
        return c;
    }

    private void index(String raw, int id, double weight) {
        String key = normalize(raw);
        if (key.isEmpty()) throw new IllegalArgumentException("empty skill name");
        Integer prev = byKey.putIfAbsent(key, id);
        if (prev != null) {
            if (prev != id) throw new IllegalArgumentException("'" + raw + "' names two skills");
            return;
        }
        tree.insert(key, weight);
        maxWords = Math.max(maxWords, key.split(" ").length);
    }

    /** Lower case, single spaces, no punctuation at either end; keeps + # . inside names (c++, c#, node.js). */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#.]+", " ").strip();
        StringBuilder out = new StringBuilder();
        for (String w : t.split(" ")) {
            String word = trimDots(w);
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(word);
        }
        return out.toString();
    }

    /** Drops trailing dots (sentence ends) and a leading dot unless it starts a name like ".net". */
    private static String trimDots(String w) {
        int end = w.length();
        while (end > 0 && w.charAt(end - 1) == '.') end--;
        return w.substring(0, end);
    }

    public Optional<Skill> get(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The skill a name or alias refers to, ignoring case and punctuation around it. */
    public Optional<Skill> resolve(String name) {
        Integer id = byKey.get(normalize(name));
        return id == null ? Optional.empty() : Optional.of(byId.get(id));
    }

    /** Skills mentioned in free text, in order of first mention. Longest match wins at each word. */
    public List<Skill> extract(String text) {
        String norm = normalize(text);
        if (norm.isEmpty()) return List.of();
        String[] words = norm.split(" ");
        Set<Integer> found = new LinkedHashSet<>();
        int i = 0;
        while (i < words.length) {
            int matched = 0;
            for (int len = Math.min(maxWords, words.length - i); len >= 1; len--) {
                Integer id = byKey.get(String.join(" ", java.util.Arrays.copyOfRange(words, i, i + len)));
                if (id != null) {
                    found.add(id);
                    matched = len;
                    break;
                }
            }
            i += Math.max(1, matched);
        }
        List<Skill> out = new ArrayList<>(found.size());
        for (int id : found) out.add(byId.get(id));
        return out;
    }

    /** Up to {@code k} skills whose name or an alias starts with {@code prefix}; names rank above aliases. */
    public List<Skill> autocomplete(String prefix, int k) {
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        String p = normalize(prefix);
        if (p.isEmpty()) return List.of();
        Set<Integer> ids = new LinkedHashSet<>();
        // Ask for extra keys, since several aliases can point at the same skill.
        for (RadixTree.Entry e : tree.complete(p, k * 4)) {
            ids.add(byKey.get(e.key()));
            if (ids.size() == k) break;
        }
        List<Skill> out = new ArrayList<>();
        for (int id : ids) out.add(byId.get(id));
        return out;
    }

    public Collection<Skill> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }
}
