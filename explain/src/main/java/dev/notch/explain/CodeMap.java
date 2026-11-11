package dev.notch.explain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A map of a codebase: what each file is, what it depends on and what depends on it, how files
 * group into modules and connected areas, where to start reading, and which files look unused.
 * It holds no source code, only facts about it, so it can be stored and shared safely.
 */
public record CodeMap(Stats stats, List<FileInfo> files, List<Module> modules, List<Area> areas,
                      List<String> startHere, List<String> entryPoints, List<String> possiblyUnused) {

    public record Stats(int files, int codeFiles, int lines, Map<String, Integer> languages, int skipped,
                        boolean truncated, List<String> filesWithSecrets) {
    }

    /**
     * @param imports    files in this codebase this file imports
     * @param importedBy files in this codebase that import it
     * @param packages   outside packages it imports (npm, PyPI, Maven, Go modules)
     * @param tools      known tools those packages are (PostgreSQL, Redis, ...), for linking decisions
     */
    public record FileInfo(String path, String language, int lines, Role role, String route, String summary,
                           List<String> symbols, List<String> endpoints, List<String> imports,
                           List<String> importedBy, List<String> packages, List<String> tools, String module,
                           int secrets) {
    }

    public record Module(String path, int files, int lines, List<String> dependsOn, List<String> usedBy) {
    }

    /** A group of files connected by imports; separate areas share no imports at all. */
    public record Area(int files, List<String> modules) {
    }

    public FileInfo file(String path) {
        for (FileInfo f : files) if (f.path().equals(path)) return f;
        return null;
    }

    /**
     * Everything that could be affected by changing {@code path}: the files that import it,
     * directly or through other files (a breadth-first walk over "imported by"), nearest first.
     */
    public List<String> affectedBy(String path) {
        Map<String, FileInfo> byPath = new HashMap<>();
        for (FileInfo f : files) byPath.put(f.path(), f);
        if (!byPath.containsKey(path)) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(path));
        seen.add(path);
        while (!queue.isEmpty()) {
            FileInfo f = byPath.get(queue.poll());
            if (f == null) continue;
            for (String importer : f.importedBy()) if (seen.add(importer)) queue.add(importer);
        }
        List<String> out = new ArrayList<>(seen);
        out.remove(path);
        return out;
    }
}
