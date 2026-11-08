package dev.notch.engine.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Groups conversations into threads: two conversations land in the same thread when they
 * share a link key (a file path, an error fingerprint, a decision topic), directly or through
 * a chain of other conversations.
 *
 * <p>Built on {@link DisjointSetUnion}, so adding a conversation costs near-constant time per
 * key and never re-scans earlier conversations. Keys that would connect almost everything
 * (for example {@code readme.md}) can be ignored.
 */
public final class ThreadStitcher {

    private final DisjointSetUnion dsu = new DisjointSetUnion();
    private final Map<String, Integer> indexOfItem = new HashMap<>();
    private final List<String> items = new ArrayList<>();
    private final Map<String, Integer> firstItemWithKey = new HashMap<>();
    private final Set<String> ignoredKeys;

    public ThreadStitcher() {
        this(Set.of());
    }

    public ThreadStitcher(Set<String> ignoredKeys) {
        Objects.requireNonNull(ignoredKeys, "ignoredKeys");
        this.ignoredKeys = ignoredKeys.stream().map(ThreadStitcher::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Adds a conversation and links it to every earlier conversation sharing a key. */
    public void addItem(String itemId, Collection<String> linkKeys) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(linkKeys, "linkKeys");
        if (indexOfItem.containsKey(itemId)) {
            throw new IllegalArgumentException("duplicate item: " + itemId);
        }
        int id = dsu.add();
        indexOfItem.put(itemId, id);
        items.add(itemId);
        for (String raw : linkKeys) {
            if (raw == null) continue;
            String key = normalize(raw);
            if (key.isEmpty() || ignoredKeys.contains(key)) continue;
            Integer first = firstItemWithKey.putIfAbsent(key, id);
            if (first != null) dsu.union(first, id);
        }
    }

    public boolean sameThread(String a, String b) {
        return dsu.connected(index(a), index(b));
    }

    /** A stable id for the thread containing {@code itemId}: the earliest item in it. */
    public String threadOf(String itemId) {
        int root = dsu.find(index(itemId));
        for (int i = 0; i < items.size(); i++) {
            if (dsu.find(i) == root) return items.get(i);
        }
        throw new IllegalStateException("unreachable");
    }

    /** All threads, keyed by their earliest item, items in insertion order. */
    public Map<String, List<String>> threads() {
        Map<Integer, List<String>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            byRoot.computeIfAbsent(dsu.find(i), r -> new ArrayList<>()).add(items.get(i));
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (List<String> group : byRoot.values()) out.put(group.get(0), List.copyOf(group));
        return out;
    }

    public int threadCount() {
        return dsu.componentCount();
    }

    public int itemCount() {
        return items.size();
    }

    private int index(String itemId) {
        Integer i = indexOfItem.get(Objects.requireNonNull(itemId, "itemId"));
        if (i == null) throw new IllegalArgumentException("unknown item: " + itemId);
        return i;
    }

    static String normalize(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
