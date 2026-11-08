package dev.notch.engine.text;

import dev.notch.engine.select.RandomizedSelect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A compressed trie (radix tree) of weighted keys, used for {@code @} autocomplete over
 * projects, file paths and symbols.
 *
 * <p>Each edge holds a whole run of characters, so long shared prefixes such as
 * {@code src/main/java/} cost one node instead of one per character. Lookup of a prefix of
 * length p walks O(p) characters. Completion collects the keys under the prefix node and
 * picks the heaviest k with randomized selection.
 */
public final class RadixTree {

    /** A stored key and its weight (higher means more relevant). */
    public record Entry(String key, double weight) {
    }

    private static final class Node {
        String edge;                                  // label from the parent
        final Map<Character, Node> children = new HashMap<>();
        boolean terminal;
        double weight;

        Node(String edge) {
            this.edge = edge;
        }
    }

    private static final Comparator<Entry> BY_RELEVANCE = Comparator
            .comparingDouble(Entry::weight)
            .thenComparing(Entry::key, Comparator.reverseOrder());  // ties: alphabetical first

    private final Node root = new Node("");
    private final RandomizedSelect select;
    private int size;

    public RadixTree() {
        this(new RandomizedSelect());
    }

    public RadixTree(RandomizedSelect select) {
        this.select = Objects.requireNonNull(select, "select");
    }

    /** Inserts {@code key}, or updates its weight if present. Returns true if it was new. */
    public boolean insert(String key, double weight) {
        validateKey(key);
        if (Double.isNaN(weight)) throw new IllegalArgumentException("weight must be a number");
        Node node = root;
        int i = 0;
        while (true) {
            if (i == key.length()) {
                return markTerminal(node, weight);
            }
            Node child = node.children.get(key.charAt(i));
            if (child == null) {
                Node leaf = new Node(key.substring(i));
                markTerminal(leaf, weight);
                node.children.put(key.charAt(i), leaf);
                return true;
            }
            int common = commonPrefix(child.edge, key, i);
            if (common == child.edge.length()) {
                node = child;
                i += common;
                continue;
            }
            // Split the edge at the first mismatch.
            Node mid = new Node(child.edge.substring(0, common));
            child.edge = child.edge.substring(common);
            mid.children.put(child.edge.charAt(0), child);
            node.children.put(key.charAt(i), mid);
            i += common;
            if (i == key.length()) {
                return markTerminal(mid, weight);
            }
            Node leaf = new Node(key.substring(i));
            markTerminal(leaf, weight);
            mid.children.put(key.charAt(i), leaf);
            return true;
        }
    }

    private boolean markTerminal(Node node, double weight) {
        boolean added = !node.terminal;
        node.terminal = true;
        node.weight = weight;
        if (added) size++;
        return added;
    }

    public boolean contains(String key) {
        validateKey(key);
        Node n = findExact(key);
        return n != null && n.terminal;
    }

    /** The weight of {@code key}, or NaN when absent. */
    public double weight(String key) {
        validateKey(key);
        Node n = findExact(key);
        return n != null && n.terminal ? n.weight : Double.NaN;
    }

    private Node findExact(String key) {
        Node node = root;
        int i = 0;
        while (i < key.length()) {
            Node child = node.children.get(key.charAt(i));
            if (child == null || !key.startsWith(child.edge, i)) return null;
            i += child.edge.length();
            node = child;
        }
        return node;
    }

    /**
     * Removes {@code key}. Nodes left with no purpose are deleted, and a node left with a
     * single child is merged into it so the tree stays compressed.
     */
    public boolean remove(String key) {
        validateKey(key);
        Deque<Node> path = new ArrayDeque<>();
        Node node = root;
        path.push(node);
        int i = 0;
        while (i < key.length()) {
            Node child = node.children.get(key.charAt(i));
            if (child == null || !key.startsWith(child.edge, i)) return false;
            i += child.edge.length();
            node = child;
            path.push(node);
        }
        if (!node.terminal) return false;
        node.terminal = false;
        node.weight = 0;
        size--;

        Node target = path.pop();
        Node parent = path.peek();
        if (target.children.isEmpty()) {
            parent.children.remove(target.edge.charAt(0));
            // The parent may now be a pass-through node with one child.
            if (parent != root && !parent.terminal && parent.children.size() == 1) {
                path.pop();
                mergeWithOnlyChild(parent, path.peek());
            }
        } else if (target.children.size() == 1) {
            mergeWithOnlyChild(target, parent);
        }
        return true;
    }

    private static void mergeWithOnlyChild(Node node, Node parent) {
        Node only = node.children.values().iterator().next();
        only.edge = node.edge + only.edge;
        parent.children.put(only.edge.charAt(0), only);
    }

    /** The {@code k} heaviest keys starting with {@code prefix} (empty prefix means all keys). */
    public List<Entry> complete(String prefix, int k) {
        Objects.requireNonNull(prefix, "prefix");
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        Node node = root;
        StringBuilder path = new StringBuilder();
        int i = 0;
        while (i < prefix.length()) {
            Node child = node.children.get(prefix.charAt(i));
            if (child == null) return List.of();
            int remaining = prefix.length() - i;
            if (remaining <= child.edge.length()) {
                // The prefix ends inside (or exactly at the end of) this edge.
                if (!child.edge.startsWith(prefix.substring(i))) return List.of();
                path.append(child.edge);
                node = child;
                i = prefix.length();
            } else {
                if (!prefix.startsWith(child.edge, i)) return List.of();
                path.append(child.edge);
                i += child.edge.length();
                node = child;
            }
        }
        List<Entry> matches = collect(node, path.toString());
        return select.topK(matches, k, BY_RELEVANCE);
    }

    private static List<Entry> collect(Node start, String startPath) {
        List<Entry> out = new ArrayList<>();
        Deque<Node> nodes = new ArrayDeque<>();
        Deque<String> paths = new ArrayDeque<>();
        nodes.push(start);
        paths.push(startPath);
        while (!nodes.isEmpty()) {
            Node n = nodes.pop();
            String p = paths.pop();
            if (n.terminal) out.add(new Entry(p, n.weight));
            for (Node c : n.children.values()) {
                nodes.push(c);
                paths.push(p + c.edge);
            }
        }
        return out;
    }

    /** Number of nodes, excluding the root. Shows how much compression saves. */
    public int nodeCount() {
        int count = 0;
        Deque<Node> stack = new ArrayDeque<>(root.children.values());
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            count++;
            stack.addAll(n.children.values());
        }
        return count;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    private static int commonPrefix(String edge, String key, int from) {
        int max = Math.min(edge.length(), key.length() - from);
        int j = 0;
        while (j < max && edge.charAt(j) == key.charAt(from + j)) j++;
        return j;
    }

    private static void validateKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty()) throw new IllegalArgumentException("key must not be empty");
    }
}
