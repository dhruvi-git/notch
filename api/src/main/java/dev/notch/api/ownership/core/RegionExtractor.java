package dev.notch.api.ownership.core;

import dev.notch.api.portfolio.core.SkillCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a repository's source files into regions worth asking about: functions, methods,
 * classes and SQL statements. Each region is tagged with the catalogue skills it shows (its
 * language, plus libraries and tools named in it) and the {@link Concept}s it uses.
 *
 * <p>Detection is by line patterns per language family (indentation for Python, braces for
 * C-like languages, semicolons for SQL), which is robust to code that doesn't compile. A file
 * with no recognisable definitions is cut into fixed windows instead, so nothing is skipped.
 */
public final class RegionExtractor {

    public record SourceFile(String path, String content) {
        public SourceFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * @param code        the region's text (up to {@link #MAX_SHOWN_LINES} lines)
     * @param identifiers names the author chose in it, most used first: evidence an answer refers to this code
     */
    public record Region(int id, String path, int startLine, int endLine, String name, String language, String code,
                         Set<Integer> skills, Set<Concept> concepts, List<String> identifiers) {
        public int lines() {
            return endLine - startLine + 1;
        }
    }

    static final int MIN_LINES = 4;
    public static final int MAX_SHOWN_LINES = 60;
    static final int WINDOW = 30;

    private static final Map<String, String> LANGUAGE = Map.ofEntries(
            Map.entry("py", "Python"), Map.entry("js", "JavaScript"), Map.entry("jsx", "JavaScript"),
            Map.entry("mjs", "JavaScript"), Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"),
            Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("go", "Go (Golang)"),
            Map.entry("rs", "Rust"), Map.entry("cs", "C#"), Map.entry("cpp", "C++"), Map.entry("cc", "C++"),
            Map.entry("hpp", "C++"), Map.entry("c", "C (language)"), Map.entry("h", "C (language)"),
            Map.entry("php", "PHP"), Map.entry("rb", "Ruby"), Map.entry("swift", "Swift"), Map.entry("dart", "Dart"),
            Map.entry("scala", "Scala"), Map.entry("sql", "SQL"), Map.entry("r", "R (language)"));

    private static final Pattern PY_DEF = Pattern.compile("^(\\s*)(?:async\\s+)?(def|class)\\s+([A-Za-z_]\\w*)");
    private static final Pattern C_DEF = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:default\\s+)?(?:public|private|protected|static|final|async|abstract|override|internal|\\s)*"
                    + "(?:(?:function\\*?|class|interface|func|fn|fun|def)\\s+([A-Za-z_$][\\w$]*)"
                    + "|(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>"
                    + "|[\\w<>\\[\\],.?\\s]+?\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;]*\\)\\s*(?:throws [\\w., ]+)?\\s*\\{)");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");
    private static final Set<String> COMMON = Set.of(
            "self", "this", "true", "false", "null", "none", "return", "import", "from", "class", "def", "function",
            "const", "public", "private", "static", "void", "string", "async", "await", "print", "length",
            "else", "elif", "while", "with", "lambda", "then", "case", "break", "continue", "throw", "throws", "catch",
            "final", "interface", "extends", "implements", "export", "default", "require", "console", "value", "values",
            "where", "select", "group", "order", "join", "left", "right", "inner", "count", "limit", "table", "create",
            "insert", "update", "delete", "into", "integer", "boolean", "float", "double", "list", "dict", "range",
            "args", "kwargs", "data", "result", "item", "items", "index", "error", "except", "assert", "type", "object");

    private RegionExtractor() {
    }

    /** The catalogue name of the language a path is written in, or null when it isn't source code Notch reads. */
    public static String language(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? null : LANGUAGE.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static List<Region> extract(List<SourceFile> files, SkillCatalog catalog) {
        List<Region> out = new ArrayList<>();
        for (SourceFile f : files) {
            String lang = language(f.path());
            if (lang == null) continue;
            String[] lines = f.content().replace("\r\n", "\n").split("\n", -1);
            List<int[]> spans = switch (lang) {
                case "Python" -> pythonSpans(lines);
                case "SQL" -> sqlSpans(lines);
                default -> braceSpans(lines);
            };
            if (spans.isEmpty()) spans = windows(lines.length);
            Map<String, Integer> imported = importedNames(f.content(), catalog);
            for (int[] span : spans) {
                int start = span[0], end = span[1];
                if (end - start + 1 < MIN_LINES) continue;
                String name = span.length > 2 && span[2] >= 0 ? nameAt(lines[start], lang) : null;
                if (name == null) name = f.path().substring(f.path().lastIndexOf('/') + 1) + ":" + (start + 1);
                int shownEnd = Math.min(end, start + MAX_SHOWN_LINES - 1);
                String code = String.join("\n", java.util.Arrays.copyOfRange(lines, start, shownEnd + 1));
                out.add(region(out.size(), f.path(), start + 1, end + 1, name, lang, code, catalog, imported));
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Region region(int id, String path, int start, int end, String name, String lang, String code,
                                 SkillCatalog catalog, Map<String, Integer> imported) {
        Set<Integer> skills = new LinkedHashSet<>();
        catalog.resolve(lang).ifPresent(s -> skills.add(s.id()));
        for (SkillCatalog.Skill s : catalog.extract(code)) skills.add(s.id());
        // A region uses a library when it uses a name the file imported from it (pd.read_csv, useEffect).
        for (Map.Entry<String, Integer> e : imported.entrySet()) {
            if (Pattern.compile("(?<![\\w.])" + Pattern.quote(e.getKey()) + "\\b").matcher(code).find()) skills.add(e.getValue());
        }

        EnumSet<Concept> concepts = EnumSet.noneOf(Concept.class);
        for (Concept c : Concept.values()) if (c.foundIn(code)) concepts.add(c);
        String bare = name.contains(":") ? null : name;
        if (bare != null && Pattern.compile("\\b" + Pattern.quote(bare) + "\\s*\\(").matcher(afterFirstLine(code)).find()) {
            concepts.add(Concept.RECURSION);
        }
        return new Region(id, path, start, end, name, lang, code, Collections.unmodifiableSet(skills),
                Collections.unmodifiableSet(concepts), identifiers(code));
    }

    private static String afterFirstLine(String code) {
        int nl = code.indexOf('\n');
        return nl < 0 ? "" : code.substring(nl + 1);
    }

    private static final Pattern PY_IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?");
    private static final Pattern PY_FROM = Pattern.compile("(?m)^\\s*from\\s+([\\w.]+)\\s+import\\s+\\(?([\\w\\s,]+)\\)?");
    private static final Pattern JS_IMPORT = Pattern.compile("(?m)^\\s*import\\s+(.+?)\\s+from\\s+[\"']([^\"']+)[\"']");
    private static final Pattern JAVA_IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\.(\\w+|\\*)\\s*;");

    /**
     * Names a file imports from a library the catalogue knows, mapped to that library's skill:
     * {@code import pandas as pd} binds pd to pandas, {@code from sklearn.x import A} binds A to
     * scikit-learn, {@code import { useEffect } from "react"} binds useEffect to React.
     */
    static Map<String, Integer> importedNames(String content, SkillCatalog catalog) {
        Map<String, Integer> out = new java.util.HashMap<>();
        Matcher m = PY_IMPORT.matcher(content);
        while (m.find()) {
            Integer skill = librarySkill(m.group(1), catalog);
            if (skill != null) out.put(m.group(2) != null ? m.group(2) : m.group(1).split("\\.")[0], skill);
        }
        m = PY_FROM.matcher(content);
        while (m.find()) {
            Integer skill = librarySkill(m.group(1), catalog);
            if (skill == null) continue;
            for (String n : m.group(2).split(",")) {
                String name = n.strip().split("\\s+")[0];
                if (!name.isEmpty()) out.put(name, skill);
            }
        }
        m = JS_IMPORT.matcher(content);
        while (m.find()) {
            Integer skill = librarySkill(m.group(2), catalog);
            if (skill == null) continue;
            for (String n : m.group(1).replaceAll("[{}*]", " ").split("[,\\s]+")) {
                if (n.matches("[A-Za-z_$][\\w$]*") && !n.equals("as") && !n.equals("type")) out.put(n, skill);
            }
        }
        m = JAVA_IMPORT.matcher(content);
        while (m.find()) {
            Integer skill = librarySkill(m.group(1), catalog);
            if (skill != null && !m.group(2).equals("*")) out.put(m.group(2), skill);
        }
        return out;
    }

    /** The skill a module path belongs to: the first of its segments the catalogue knows ("sklearn.model_selection" → scikit-learn). */
    private static Integer librarySkill(String module, SkillCatalog catalog) {
        for (String part : module.replace('@', ' ').split("[./\\s]+")) {
            if (part.length() < 2) continue;
            var skill = catalog.resolve(part);
            if (skill.isPresent()) return skill.get().id();
        }
        return null;
    }

    /** Names the author chose, most used first: words of 4+ characters that aren't keywords or common words. */
    static List<String> identifiers(String code) {
        Map<String, Integer> count = new java.util.LinkedHashMap<>();
        Matcher m = IDENT.matcher(code.replaceAll("(?s)\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'", " "));
        while (m.find()) {
            String w = m.group();
            if (COMMON.contains(w.toLowerCase(Locale.ROOT))) continue;
            count.merge(w, 1, Integer::sum);
        }
        return count.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8).map(Map.Entry::getKey).toList();
    }

    /** Python: a def or class runs until the next line at its own indentation or less. */
    static List<int[]> pythonSpans(String[] lines) {
        List<int[]> spans = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = PY_DEF.matcher(lines[i]);
            if (!m.find()) continue;
            int indent = m.group(1).length();
            if (m.group(2).equals("class")) continue; // methods inside are the regions; a class alone says little
            int end = i;
            for (int j = i + 1; j < lines.length; j++) {
                String l = lines[j];
                if (l.isBlank()) continue;
                int ind = l.length() - l.stripLeading().length();
                if (ind <= indent) break;
                end = j;
            }
            spans.add(new int[]{i, end, 0});
        }
        return spans;
    }

    /** C-like languages: a declaration line followed by its brace-balanced body. Nested definitions are left inside. */
    static List<int[]> braceSpans(String[] lines) {
        List<int[]> spans = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (!isDeclaration(lines[i]) || lines[i].strip().startsWith("//")) {
                i++;
                continue;
            }
            int depth = 0;
            boolean opened = false;
            int end = -1;
            for (int j = i; j < lines.length && j < i + 400; j++) {
                for (char ch : stripStringsAndComments(lines[j]).toCharArray()) {
                    if (ch == '{') {
                        depth++;
                        opened = true;
                    } else if (ch == '}') {
                        depth--;
                    }
                }
                if (opened && depth <= 0) {
                    end = j;
                    break;
                }
                if (!opened && j > i + 2) break; // no body: a declaration only
            }
            if (end < 0) {
                i++;
                continue;
            }
            // A class body holds the methods worth asking about: step inside instead of taking the class whole.
            if (lines[i].matches(".*\\b(class|interface)\\b.*") && end - i > 15) {
                i++;
                continue;
            }
            spans.add(new int[]{i, end, 0});
            i = end + 1;
        }
        return spans;
    }

    static boolean isDeclaration(String line) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("if ") || t.startsWith("if(") || t.startsWith("for") || t.startsWith("while")
                || t.startsWith("switch") || t.startsWith("return") || t.startsWith("else") || t.startsWith("catch")
                || t.startsWith("}")) {
            return false;
        }
        return C_DEF.matcher(line).find();
    }

    private static String stripStringsAndComments(String line) {
        String noStrings = line.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|`(?:[^`\\\\]|\\\\.)*`", "\"\"");
        int c = noStrings.indexOf("//");
        return c >= 0 ? noStrings.substring(0, c) : noStrings;
    }

    /** SQL: one region per statement. */
    static List<int[]> sqlSpans(String[] lines) {
        List<int[]> spans = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (start < 0 && (t.isEmpty() || t.startsWith("--"))) continue;
            if (start < 0) start = i;
            if (t.endsWith(";")) {
                spans.add(new int[]{start, i, -1});
                start = -1;
            }
        }
        if (start >= 0) spans.add(new int[]{start, lines.length - 1, -1});
        return spans;
    }

    static List<int[]> windows(int n) {
        List<int[]> spans = new ArrayList<>();
        for (int s = 0; s < n; s += WINDOW) spans.add(new int[]{s, Math.min(n, s + WINDOW) - 1, -1});
        return spans;
    }

    private static String nameAt(String line, String lang) {
        if (lang.equals("Python")) {
            Matcher m = PY_DEF.matcher(line);
            return m.find() ? m.group(3) : null;
        }
        Matcher m = C_DEF.matcher(line);
        if (!m.find()) return null;
        for (int g = 1; g <= 3; g++) if (m.group(g) != null) return m.group(g);
        return null;
    }
}
