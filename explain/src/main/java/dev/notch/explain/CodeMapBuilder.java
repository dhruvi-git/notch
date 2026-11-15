package dev.notch.explain;

import dev.notch.engine.graph.DisjointSetUnion;
import dev.notch.engine.select.RandomizedSelect;
import dev.notch.engine.text.Kmp;
import dev.notch.engine.text.SecretScanner;
import dev.notch.guard.TechCatalog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a {@link CodeMap} from a codebase's files.
 *
 * <ol>
 *   <li>Skips what isn't source: dependency and build folders, lockfiles, binaries, huge files.</li>
 *   <li>Reads each file on its own ({@link FileFacts}).</li>
 *   <li>Resolves imports to files in the codebase; the rest are outside packages, which are
 *       matched to known tools (so a file importing {@code pg} is linked to PostgreSQL).</li>
 *   <li>Java and Kotlin classes use same-package classes without importing them, so those uses
 *       are found by searching each file for its neighbours' class names with {@link Kmp}.</li>
 *   <li>Groups files into modules (folders big enough to mean something) and into connected
 *       areas with union-find ({@link DisjointSetUnion}).</li>
 *   <li>Picks the files to read first, the ones most of the code depends on, with randomized
 *       selection ({@link RandomizedSelect#topK}) instead of sorting every file.</li>
 *   <li>Walks breadth-first from every root (pages, routes, controllers, entry points, tests,
 *       scripts) along imports; code files never reached are reported as possibly unused.</li>
 * </ol>
 *
 * <p>Cost: reading is linear in the size of the code; the graph steps are O(files + imports).
 */
public final class CodeMapBuilder {

    public static final int MAX_FILES = 5000;
    public static final int MAX_FILE_BYTES = 300_000;
    public static final int START_HERE = 10;

    private static final Set<String> SKIPPED_DIRS = Set.of(
            "node_modules", ".git", "dist", "build", "target", ".next", "out", "coverage", "venv", ".venv", "env",
            "__pycache__", ".idea", ".vscode", "vendor", ".gradle", ".mvn", ".turbo", ".cache", ".pytest_cache",
            ".mypy_cache", "bin-release", ".svelte-kit", ".nuxt", ".expo", "Pods", ".terraform");
    private static final Set<String> SKIPPED_FILES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock", "pipfile.lock", "cargo.lock", "go.sum",
            "gemfile.lock", "composer.lock", "bun.lockb", "uv.lock", ".ds_store", "next-env.d.ts");
    private static final Set<String> BINARY = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "svg", "pdf", "zip", "gz", "tar", "jar", "class", "war", "woff",
            "woff2", "ttf", "otf", "eot", "mp4", "mp3", "wav", "mov", "exe", "dll", "so", "dylib", "bin", "dat", "db",
            "sqlite", "pyc", "lock", "map", "bundle");
    private static final Set<String> NODE_BUILTINS = Set.of(
            "fs", "path", "os", "http", "https", "url", "crypto", "util", "stream", "events", "child_process",
            "assert", "buffer", "zlib", "net", "tls", "readline", "process", "timers", "worker_threads", "querystring");
    private static final Set<String> PY_STDLIB = Set.of(
            "os", "sys", "re", "json", "typing", "datetime", "time", "math", "collections", "itertools", "functools",
            "pathlib", "subprocess", "logging", "unittest", "dataclasses", "abc", "asyncio", "random", "uuid", "hashlib",
            "base64", "io", "csv", "enum", "copy", "argparse", "threading", "http", "urllib", "socket", "shutil",
            "tempfile", "glob", "string", "textwrap", "decimal", "statistics", "inspect", "traceback", "contextlib",
            "operator", "heapq", "bisect", "queue", "pickle", "sqlite3", "email", "html", "xml", "zipfile", "gzip",
            "secrets", "signal", "struct", "types", "warnings", "weakref", "pprint", "__future__", "concurrent");
    private static final String[] JS_EXTENSIONS = {".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts"};

    private final SecretScanner secrets = new SecretScanner();

    public CodeMap build(List<SourceFile> input) {
        // 1. Keep source files only.
        List<SourceFile> files = new ArrayList<>();
        int skipped = 0;
        for (SourceFile f : input) {
            if (!keep(f)) {
                skipped++;
                continue;
            }
            files.add(f);
        }
        files.sort(Comparator.comparing(SourceFile::path));
        boolean truncated = files.size() > MAX_FILES;
        if (truncated) {
            skipped += files.size() - MAX_FILES;
            files = new ArrayList<>(files.subList(0, MAX_FILES));
        }

        // 2. Read each file.
        Map<String, FileFacts> facts = new LinkedHashMap<>();
        Map<String, String> content = new HashMap<>();
        for (SourceFile f : files) {
            facts.put(f.path(), new FileFacts(f.path(), f.content()));
            content.put(f.path(), f.content());
        }

        // 3. Resolve imports.
        Resolver resolver = new Resolver(facts, content);
        Map<String, Set<String>> imports = new LinkedHashMap<>();
        Map<String, Set<String>> packages = new HashMap<>();
        for (FileFacts f : facts.values()) {
            Set<String> internal = new LinkedHashSet<>();
            Set<String> external = new LinkedHashSet<>();
            if (f.followsImports()) {
                for (String raw : f.rawImports) resolver.resolve(f, raw, internal, external);
            }
            internal.remove(f.path);
            imports.put(f.path, internal);
            packages.put(f.path, external);
        }

        // 4. Same-package class uses in Java and Kotlin, found with KMP.
        javaSamePackageUses(facts, content, imports);

        Map<String, Set<String>> importedBy = new LinkedHashMap<>();
        for (String p : facts.keySet()) importedBy.put(p, new LinkedHashSet<>());
        imports.forEach((from, tos) -> tos.forEach(to -> importedBy.get(to).add(from)));

        // 5. Modules and connected areas.
        Map<String, String> moduleOf = modules(facts);
        List<CodeMap.Area> areas = areas(facts, imports, moduleOf);

        // 6. Where to start reading: the code most other code depends on.
        List<FileFacts> candidates = facts.values().stream()
                .filter(f -> f.followsImports() && f.role != Role.TEST && !importedBy.get(f.path).isEmpty()).toList();
        List<String> startHere = candidates.isEmpty() ? List.of()
                : new RandomizedSelect(7).topK(candidates, Math.min(START_HERE, candidates.size()),
                        Comparator.<FileFacts>comparingInt(f -> importedBy.get(f.path).size())
                                .thenComparingInt(f -> f.symbols.size())
                                .thenComparing(f -> f.path, Comparator.reverseOrder()))
                .stream().map(f -> f.path).toList();

        // 7. Entry points, and code no root reaches.
        List<String> entryPoints = facts.values().stream()
                .filter(f -> f.role == Role.ENTRY || f.role == Role.PAGE || f.role == Role.API_ROUTE || f.role == Role.CONTROLLER)
                .map(f -> f.path).toList();
        List<String> unused = unreachable(facts, imports, content);

        // 8. Assemble.
        List<CodeMap.FileInfo> infos = new ArrayList<>();
        Map<String, Integer> languages = new TreeMap<>();
        List<String> withSecrets = new ArrayList<>();
        int lines = 0;
        int codeFiles = 0;
        for (FileFacts f : facts.values()) {
            int found = secrets.scan(content.get(f.path)).findings().size();
            if (found > 0) withSecrets.add(f.path);
            if (f.language != null) languages.merge(f.language, 1, Integer::sum);
            if (f.followsImports()) codeFiles++;
            lines += f.lines;
            List<String> pkgs = new ArrayList<>(packages.get(f.path));
            infos.add(new CodeMap.FileInfo(f.path, f.language, f.lines, f.role, f.route, f.summary,
                    List.copyOf(f.symbols), List.copyOf(f.endpoints), List.copyOf(imports.get(f.path)),
                    List.copyOf(importedBy.get(f.path)), pkgs, tools(pkgs), moduleOf.get(f.path), found));
        }
        CodeMap.Stats stats = new CodeMap.Stats(infos.size(), codeFiles, lines, languages, skipped, truncated, withSecrets);
        return new CodeMap(stats, infos, moduleInfos(facts, imports, importedBy, moduleOf), areas, startHere, entryPoints, unused);
    }

    // ------------------------------------------------------------------ filtering

    /** True for paths inside dependency, VCS or build folders, which are never part of a map. */
    public static boolean inSkippedFolder(String path) {
        String[] segs = path.replace('\\', '/').split("/");
        for (int i = 0; i < segs.length - 1; i++) if (SKIPPED_DIRS.contains(segs[i])) return true;
        return false;
    }

    static boolean keep(SourceFile f) {
        String p = f.path();
        String[] segs = p.split("/");
        for (int i = 0; i < segs.length - 1; i++) if (SKIPPED_DIRS.contains(segs[i])) return false;
        String n = segs[segs.length - 1].toLowerCase(Locale.ROOT);
        if (SKIPPED_FILES.contains(n) || n.endsWith(".min.js") || n.endsWith(".min.css")) return false;
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && BINARY.contains(n.substring(dot + 1))) return false;
        if (f.content().length() > MAX_FILE_BYTES) return false;
        if (f.content().indexOf('\0') >= 0) return false;  // binary content in a text-looking name
        return FileFacts.languageOf(p) != null || n.equals("dockerfile") || n.equals("makefile");
    }

    // ------------------------------------------------------------------ resolving imports

    /** Turns an import as written into files of this codebase, or an outside package name. */
    private static final class Resolver {
        private final Map<String, FileFacts> facts;
        private final Map<String, List<String>> javaPackages = new HashMap<>();
        private final Map<String, String> javaClasses = new HashMap<>();
        private final Map<String, List<String>> dirFiles = new HashMap<>();
        private String goModule;
        private String goRoot = "";

        Resolver(Map<String, FileFacts> facts, Map<String, String> content) {
            this.facts = facts;
            for (FileFacts f : facts.values()) {
                dirFiles.computeIfAbsent(dir(f.path), k -> new ArrayList<>()).add(f.path);
                if (f.javaPackage != null) {
                    javaPackages.computeIfAbsent(f.javaPackage, k -> new ArrayList<>()).add(f.path);
                    String cls = f.name.replaceAll("\\.(java|kt)$", "");
                    javaClasses.put(f.javaPackage + "." + cls, f.path);
                }
                if (f.name.equals("go.mod")) {
                    for (String line : content.get(f.path).split("\n")) {
                        if (line.startsWith("module ")) {
                            goModule = line.substring(7).strip();
                            goRoot = dir(f.path);
                        }
                    }
                }
            }
        }

        void resolve(FileFacts from, String raw, Set<String> internal, Set<String> external) {
            switch (from.language) {
                case "TypeScript", "JavaScript" -> js(from, raw, internal, external);
                case "Python" -> python(from, raw, internal, external);
                case "Java", "Kotlin" -> java(raw, internal, external);
                case "Go" -> go(raw, internal, external);
                default -> {
                }
            }
        }

        private void js(FileFacts from, String spec, Set<String> internal, Set<String> external) {
            List<String> bases = new ArrayList<>();
            if (spec.startsWith(".")) {
                bases.add(normalize(dir(from.path) + "/" + spec));
            } else if (spec.startsWith("@/") || spec.startsWith("~/")) {
                String rest = spec.substring(2);
                int src = from.path.lastIndexOf("src/");
                if (src >= 0) bases.add(from.path.substring(0, src + 4) + rest);
                bases.add("src/" + rest);
                bases.add(rest);
            } else {
                String s = spec.startsWith("node:") ? spec.substring(5) : spec;
                String[] parts = s.split("/");
                String pkg = s.startsWith("@") && parts.length > 1 ? parts[0] + "/" + parts[1] : parts[0];
                if (!NODE_BUILTINS.contains(pkg) && !spec.startsWith("node:")) external.add(pkg);
                return;
            }
            for (String base : bases) {
                String hit = jsFile(base);
                if (hit != null) {
                    internal.add(hit);
                    return;
                }
            }
        }

        private String jsFile(String base) {
            if (facts.containsKey(base)) return base;
            String stem = base.replaceAll("\\.(js|jsx|mjs)$", "");  // ESM imports of .ts files name them .js
            for (String ext : JS_EXTENSIONS) if (facts.containsKey(stem + ext)) return stem + ext;
            for (String ext : JS_EXTENSIONS) if (facts.containsKey(base + "/index" + ext)) return base + "/index" + ext;
            return null;
        }

        private void python(FileFacts from, String raw, Set<String> internal, Set<String> external) {
            int bar = raw.indexOf('|');
            String module = raw.substring(0, bar);
            String name = raw.substring(bar + 1);
            List<String> bases = new ArrayList<>();
            if (module.startsWith(".")) {
                int dots = 0;
                while (dots < module.length() && module.charAt(dots) == '.') dots++;
                String d = dir(from.path);
                for (int i = 1; i < dots; i++) d = dir(d);
                String rest = module.substring(dots).replace('.', '/');
                bases.add(rest.isEmpty() ? d : (d.isEmpty() ? rest : d + "/" + rest));
            } else {
                String rel = module.replace('.', '/');
                // Absolute imports resolve from any folder above the importing file (project roots vary).
                for (String d = dir(from.path); ; d = dir(d)) {
                    bases.add(d.isEmpty() ? rel : d + "/" + rel);
                    if (d.isEmpty()) break;
                }
                bases.add("src/" + rel);
            }
            for (String base : bases) {
                List<String> hits = new ArrayList<>();
                if (!name.isEmpty() && facts.containsKey(base + "/" + name + ".py")) hits.add(base + "/" + name + ".py");
                if (facts.containsKey(base + ".py")) hits.add(base + ".py");
                else if (facts.containsKey(base + "/__init__.py")) hits.add(base + "/__init__.py");
                if (!hits.isEmpty()) {
                    internal.addAll(hits);
                    return;
                }
            }
            if (!module.startsWith(".")) {
                String top = module.split("\\.")[0];
                if (!PY_STDLIB.contains(top) && !top.isEmpty()) external.add(top);
            }
        }

        private void java(String raw, Set<String> internal, Set<String> external) {
            if (raw.endsWith(".*")) {
                List<String> pkg = javaPackages.get(raw.substring(0, raw.length() - 2));
                if (pkg != null) {
                    internal.addAll(pkg);
                    return;
                }
            } else {
                // a.b.C, or a.b.C.Inner / a.b.C.member: try shorter names until one is a class here.
                String name = raw;
                while (name.contains(".")) {
                    String hit = javaClasses.get(name);
                    if (hit != null) {
                        internal.add(hit);
                        return;
                    }
                    name = name.substring(0, name.lastIndexOf('.'));
                }
            }
            String clean = raw.replaceAll("\\.\\*$", "");
            if (clean.startsWith("java.") || clean.startsWith("javax.") || clean.startsWith("kotlin.")) return;
            String[] parts = clean.split("\\.");
            external.add(parts.length >= 2 ? parts[0] + "." + parts[1] : clean);
        }

        private void go(String raw, Set<String> internal, Set<String> external) {
            if (goModule != null && (raw.equals(goModule) || raw.startsWith(goModule + "/"))) {
                String rel = raw.substring(goModule.length()).replaceAll("^/", "");
                String d = goRoot.isEmpty() ? rel : rel.isEmpty() ? goRoot : goRoot + "/" + rel;
                for (String p : dirFiles.getOrDefault(d, List.of())) {
                    if (p.endsWith(".go") && !p.endsWith("_test.go")) internal.add(p);
                }
                return;
            }
            if (!raw.contains(".")) return;  // the standard library has no dots ("fmt", "net/http")
            String[] parts = raw.split("/");
            external.add(parts.length >= 3 ? parts[0] + "/" + parts[1] + "/" + parts[2] : raw);
        }
    }

    private static void javaSamePackageUses(Map<String, FileFacts> facts, Map<String, String> content,
                                            Map<String, Set<String>> imports) {
        Map<String, List<FileFacts>> byPackage = new HashMap<>();
        for (FileFacts f : facts.values()) {
            if (f.javaPackage != null) byPackage.computeIfAbsent(f.javaPackage, k -> new ArrayList<>()).add(f);
        }
        for (List<FileFacts> pkg : byPackage.values()) {
            if (pkg.size() < 2 || pkg.size() > 400) continue;
            for (FileFacts user : pkg) {
                String text = content.get(user.path);
                for (FileFacts used : pkg) {
                    if (used == user) continue;
                    String cls = used.name.replaceAll("\\.(java|kt)$", "");
                    if (usesWord(text, cls)) imports.get(user.path).add(used.path);
                }
            }
        }
    }

    /** Whether {@code word} appears in {@code text} as a whole identifier, found with KMP. */
    static boolean usesWord(String text, String word) {
        for (int at : Kmp.findAll(text, word, false)) {
            boolean before = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
            int end = at + word.length();
            boolean after = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
            if (before && after) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ structure

    /** Each file's module: the folder it's in ("(root)" for files at the top). */
    private static Map<String, String> modules(Map<String, FileFacts> facts) {
        Map<String, String> out = new HashMap<>();
        for (String p : facts.keySet()) out.put(p, dir(p).isEmpty() ? "(root)" : dir(p));
        return out;
    }

    private static List<CodeMap.Module> moduleInfos(Map<String, FileFacts> facts, Map<String, Set<String>> imports,
                                                    Map<String, Set<String>> importedBy, Map<String, String> moduleOf) {
        Map<String, int[]> size = new TreeMap<>();
        Map<String, Set<String>> deps = new HashMap<>();
        Map<String, Set<String>> users = new HashMap<>();
        for (FileFacts f : facts.values()) {
            String m = moduleOf.get(f.path);
            int[] s = size.computeIfAbsent(m, k -> new int[2]);
            s[0]++;
            s[1] += f.lines;
            for (String to : imports.get(f.path)) {
                String mt = moduleOf.get(to);
                if (!mt.equals(m)) {
                    deps.computeIfAbsent(m, k -> new java.util.TreeSet<>()).add(mt);
                    users.computeIfAbsent(mt, k -> new java.util.TreeSet<>()).add(m);
                }
            }
        }
        List<CodeMap.Module> out = new ArrayList<>();
        size.forEach((m, s) -> out.add(new CodeMap.Module(m, s[0], s[1],
                List.copyOf(deps.getOrDefault(m, Set.of())), List.copyOf(users.getOrDefault(m, Set.of())))));
        return out;
    }

    /** Connected groups of code files (union-find over import links), largest first. */
    private static List<CodeMap.Area> areas(Map<String, FileFacts> facts, Map<String, Set<String>> imports,
                                            Map<String, String> moduleOf) {
        List<String> code = facts.values().stream().filter(FileFacts::followsImports).map(f -> f.path).toList();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < code.size(); i++) index.put(code.get(i), i);
        DisjointSetUnion dsu = DisjointSetUnion.ofSize(code.size());
        for (String from : code) {
            for (String to : imports.get(from)) {
                Integer j = index.get(to);
                if (j != null) dsu.union(index.get(from), j);
            }
        }
        Map<Integer, List<String>> groups = new HashMap<>();
        for (int i = 0; i < code.size(); i++) groups.computeIfAbsent(dsu.find(i), k -> new ArrayList<>()).add(code.get(i));
        List<CodeMap.Area> out = new ArrayList<>();
        for (List<String> g : groups.values()) {
            if (g.size() < 2) continue;  // a file on its own isn't an area
            Map<String, Integer> mods = new HashMap<>();
            for (String p : g) mods.merge(moduleOf.get(p), 1, Integer::sum);
            List<String> top = mods.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(4).map(Map.Entry::getKey).toList();
            out.add(new CodeMap.Area(g.size(), top));
        }
        out.sort(Comparator.comparingInt(CodeMap.Area::files).reversed()
                .thenComparing(a -> a.modules().isEmpty() ? "" : a.modules().get(0)));
        return out;
    }

    /**
     * Code files that nothing run directly can reach: a breadth-first walk along imports from
     * every root (pages, routes, controllers, framework-managed classes, entry points, tests,
     * scripts), plus files that package.json scripts or main/bin point at.
     */
    private static List<String> unreachable(Map<String, FileFacts> facts, Map<String, Set<String>> imports,
                                            Map<String, String> content) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (FileFacts f : facts.values()) {
            if (f.role.root && seen.add(f.path)) queue.add(f.path);
        }
        for (FileFacts f : facts.values()) {
            if (!f.name.equals("package.json")) continue;
            String pkg = content.get(f.path);
            String base = dir(f.path);
            for (FileFacts g : facts.values()) {
                String rel = base.isEmpty() ? g.path : g.path.startsWith(base + "/") ? g.path.substring(base.length() + 1) : null;
                if (rel != null && pkg.contains(rel) && seen.add(g.path)) queue.add(g.path);
            }
        }
        while (!queue.isEmpty()) {
            for (String next : imports.getOrDefault(queue.poll(), Set.of())) if (seen.add(next)) queue.add(next);
        }
        List<String> out = new ArrayList<>();
        for (FileFacts f : facts.values()) {
            // Type declaration files and index re-export files are often reached in ways imports don't show.
            if (f.followsImports() && !seen.contains(f.path) && !f.name.endsWith(".d.ts")) out.add(f.path);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> tools(List<String> pkgs) {
        Set<String> names = new LinkedHashSet<>();
        for (String p : pkgs) for (TechCatalog.Tech t : TechCatalog.find(p.replace('/', ' '))) names.add(t.name());
        return List.copyOf(names);
    }

    static String dir(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    static String normalize(String path) {
        Deque<String> out = new ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!out.isEmpty()) out.removeLast();
            } else {
                out.addLast(seg);
            }
        }
        return String.join("/", out);
    }
}
