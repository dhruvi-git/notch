package dev.notch.api.explain.core;

import dev.notch.explain.CodeMap;
import dev.notch.explain.CodeMapBuilder;

import java.util.List;

/** Sanity limits for maps uploaded by the CLI, which arrive as untrusted JSON. */
public final class MapLimits {

    static final int MAX_PATH = 500;
    static final int MAX_TEXT = 400;
    static final int MAX_LIST = 2000;

    private MapLimits() {
    }

    /** What's wrong with the map, in plain words, or null if it's acceptable. */
    public static String problem(CodeMap m) {
        if (m == null || m.stats() == null || m.files() == null) return "The map is missing or incomplete.";
        if (m.files().isEmpty()) return "The map has no files. Run notch map from inside the repository.";
        if (m.files().size() > CodeMapBuilder.MAX_FILES) return "A map can hold at most " + CodeMapBuilder.MAX_FILES + " files.";
        for (CodeMap.FileInfo f : m.files()) {
            if (f.path() == null || f.path().isBlank() || f.path().length() > MAX_PATH) return "Every file needs a path under 500 characters.";
            if (f.role() == null) return "Every file needs a role.";
            if (f.summary() != null && f.summary().length() > MAX_TEXT) return "Summaries must be under 400 characters.";
            for (List<String> l : List.of(nz(f.symbols()), nz(f.endpoints()), nz(f.imports()), nz(f.importedBy()), nz(f.packages()), nz(f.tools()))) {
                if (l.size() > MAX_LIST) return "A file lists too many items.";
            }
        }
        return null;
    }

    private static List<String> nz(List<String> l) {
        return l == null ? List.of() : l;
    }
}
