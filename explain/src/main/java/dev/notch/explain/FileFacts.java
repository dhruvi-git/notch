package dev.notch.explain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What can be read from one file on its own: language, role, raw imports, exported names,
 * API endpoints and a one-line summary. Regular expressions, not full parsers: they cover the
 * common forms in TypeScript/JavaScript, Python, Java/Kotlin and Go.
 */
final class FileFacts {

    final String path;
    final String name;
    final String language;
    final int lines;
    Role role = Role.CODE;
    String route;
    String summary = "";
    final List<String> rawImports = new ArrayList<>();
    final Set<String> symbols = new LinkedHashSet<>();
    final List<String> endpoints = new ArrayList<>();
    String javaPackage;

    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"), Map.entry("mts", "TypeScript"),
            Map.entry("cts", "TypeScript"), Map.entry("js", "JavaScript"), Map.entry("jsx", "JavaScript"),
            Map.entry("mjs", "JavaScript"), Map.entry("cjs", "JavaScript"), Map.entry("py", "Python"),
            Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("kts", "Kotlin"), Map.entry("go", "Go"),
            Map.entry("rb", "Ruby"), Map.entry("rs", "Rust"), Map.entry("cs", "C#"), Map.entry("php", "PHP"),
            Map.entry("swift", "Swift"), Map.entry("sql", "SQL"), Map.entry("css", "CSS"), Map.entry("scss", "CSS"),
            Map.entry("sass", "CSS"), Map.entry("less", "CSS"), Map.entry("html", "HTML"), Map.entry("vue", "Vue"),
            Map.entry("svelte", "Svelte"), Map.entry("md", "Markdown"), Map.entry("mdx", "Markdown"),
            Map.entry("json", "JSON"), Map.entry("yml", "YAML"), Map.entry("yaml", "YAML"), Map.entry("toml", "TOML"),
            Map.entry("xml", "XML"), Map.entry("sh", "Shell"), Map.entry("ps1", "Shell"), Map.entry("properties", "Config"),
            Map.entry("txt", "Text"), Map.entry("env", "Config"), Map.entry("gradle", "Gradle"));

    /** Languages whose imports are followed to build the dependency graph. */
    static final Set<String> GRAPH_LANGUAGES = Set.of("TypeScript", "JavaScript", "Python", "Java", "Kotlin", "Go");

    private static final Set<String> CONFIG_NAMES = Set.of(
            "package.json", "tsconfig.json", "jsconfig.json", "pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts", "go.mod", "cargo.toml", "pyproject.toml", "setup.py", "setup.cfg",
            "pipfile", "dockerfile", "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml",
            ".env.example", "application.yml", "application.yaml", "application.properties", "vercel.json",
            "netlify.toml", "makefile", ".eslintrc.json", ".prettierrc", "babel.config.js");

    FileFacts(String path, String content) {
        this.path = path;
        this.name = path.substring(path.lastIndexOf('/') + 1);
        this.language = languageOf(path);
        int n = content.isEmpty() ? 0 : content.split("\n", -1).length;
        this.lines = content.endsWith("\n") ? n - 1 : n;  // a final newline ends the last line, it doesn't start one
        read(content);
    }

    static String languageOf(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (n.equals("dockerfile") || n.startsWith("dockerfile.")) return "Docker";
        if (n.equals("makefile")) return "Make";
        // Files named by convention rather than extension.
        if (n.equals("go.mod") || n.startsWith(".env") || n.equals(".gitignore") || n.equals(".dockerignore")) return "Config";
        int dot = n.lastIndexOf('.');
        return dot < 0 ? null : LANGUAGES.get(n.substring(dot + 1));
    }

    boolean followsImports() {
        return language != null && GRAPH_LANGUAGES.contains(language);
    }

    // ------------------------------------------------------------------ reading

    private void read(String c) {
        if (language == null) return;
        switch (language) {
            case "TypeScript", "JavaScript" -> readJs(c);
            case "Python" -> readPython(c);
            case "Java", "Kotlin" -> readJava(c);
            case "Go" -> readGo(c);
            default -> {
            }
        }
        decideRole(c);
        String doc = docSummary(c);
        summary = doc != null ? doc : generatedSummary();
    }

    private static final Pattern JS_IMPORT = Pattern.compile(
            "(?:\\bimport|\\bexport)\\s[^'\";]*?\\bfrom\\s*['\"]([^'\"\\n]+)['\"]"
                    + "|\\bimport\\s*['\"]([^'\"\\n]+)['\"]"
                    + "|\\brequire\\(\\s*['\"]([^'\"\\n]+)['\"]\\s*\\)"
                    + "|\\bimport\\(\\s*['\"]([^'\"\\n]+)['\"]\\s*\\)");
    private static final Pattern JS_EXPORT = Pattern.compile(
            "(?m)^\\s*export\\s+(?:default\\s+)?(?:async\\s+)?(?:function\\*?|class|const|let|var|interface|type|enum)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern JS_EXPORT_LIST = Pattern.compile("(?m)^\\s*export\\s*\\{([^}]*)\\}");
    private static final Pattern NEXT_METHODS = Pattern.compile(
            "export\\s+(?:async\\s+)?function\\s+(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\b|\\bas\\s+(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\b");
    private static final Pattern EXPRESS = Pattern.compile(
            "\\b(?:app|router|server|api)\\.(get|post|put|patch|delete)\\(\\s*['\"`](/[^'\"`]*)['\"`]");

    private void readJs(String c) {
        Matcher m = JS_IMPORT.matcher(stripJsComments(c));
        while (m.find()) {
            for (int g = 1; g <= 4; g++) if (m.group(g) != null) rawImports.add(m.group(g));
        }
        m = JS_EXPORT.matcher(c);
        while (m.find()) symbols.add(m.group(1));
        m = JS_EXPORT_LIST.matcher(c);
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                String[] asParts = part.trim().split("\\s+as\\s+");
                String exported = asParts[asParts.length - 1].trim();
                if (exported.matches("[A-Za-z_$][\\w$]*") && !exported.equals("default")) symbols.add(exported);
            }
        }
        m = EXPRESS.matcher(c);
        while (m.find()) endpoints.add(m.group(1).toUpperCase(Locale.ROOT) + " " + m.group(2));
    }

    private static final Pattern PY_FROM = Pattern.compile(
            "(?m)^[ \\t]*from[ \\t]+(\\.*[\\w.]*)[ \\t]+import[ \\t]+(?:\\(([^)]*)\\)|([\\w \\t,.*]+))");
    private static final Pattern PY_IMPORT = Pattern.compile("(?m)^[ \\t]*import[ \\t]+([\\w.]+(?:[ \\t]*,[ \\t]*[\\w.]+)*)");
    private static final Pattern PY_DEF = Pattern.compile("(?m)^(?:async\\s+)?(?:def|class)\\s+([A-Za-z]\\w*)");
    private static final Pattern PY_ROUTE = Pattern.compile(
            "(?m)^\\s*@\\w+\\.(get|post|put|patch|delete|route)\\(\\s*['\"]([^'\"]+)['\"]");

    private void readPython(String c) {
        Matcher m = PY_FROM.matcher(c);
        while (m.find()) {
            String module = m.group(1);
            String names = m.group(2) != null ? m.group(2) : m.group(3);
            // "from . import a, b" names modules; "from x import y" may name a submodule.
            for (String n : names.split(",")) {
                String name = n.trim().split("\\s+")[0];
                if (name.matches("\\w+")) rawImports.add(module + "|" + name);
            }
        }
        m = PY_IMPORT.matcher(c);
        while (m.find()) for (String mod : m.group(1).split(",")) rawImports.add(mod.trim() + "|");
        m = PY_DEF.matcher(c);
        while (m.find()) symbols.add(m.group(1));
        m = PY_ROUTE.matcher(c);
        while (m.find()) {
            String verb = m.group(1).equals("route") ? "ANY" : m.group(1).toUpperCase(Locale.ROOT);
            endpoints.add(verb + " " + m.group(2));
        }
    }

    private static final Pattern JAVA_PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
    private static final Pattern JAVA_IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;?\\s*$");
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "(?m)^\\s*(?:public\\s+|internal\\s+)?(?:(?:final|abstract|sealed|static|data|open)\\s+)*(?:class|interface|record|enum|@interface|object)\\s+([A-Z]\\w*)");
    private static final Pattern SPRING_PREFIX = Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern SPRING_METHOD = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete)Mapping(?:\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?\"([^\"]*)\")?");

    private void readJava(String c) {
        Matcher m = JAVA_PACKAGE.matcher(c);
        if (m.find()) javaPackage = m.group(1);
        m = JAVA_IMPORT.matcher(c);
        while (m.find()) rawImports.add(m.group(1) + (m.group(2) != null ? ".*" : ""));
        m = JAVA_TYPE.matcher(c);
        while (m.find()) symbols.add(m.group(1));
        String prefix = "";
        m = SPRING_PREFIX.matcher(c);
        if (m.find()) prefix = m.group(1);
        m = SPRING_METHOD.matcher(c);
        while (m.find()) {
            String p = (prefix + (m.group(2) == null ? "" : m.group(2))).replaceAll("//+", "/");
            endpoints.add(m.group(1).toUpperCase(Locale.ROOT) + " " + (p.isEmpty() ? "/" : p));
        }
    }

    private static final Pattern GO_IMPORT_ONE = Pattern.compile("(?m)^\\s*import\\s+(?:[\\w.]+\\s+)?\"([^\"]+)\"");
    private static final Pattern GO_IMPORT_BLOCK = Pattern.compile("(?s)import\\s*\\((.*?)\\)");
    private static final Pattern GO_QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern GO_EXPORT = Pattern.compile("(?m)^(?:func\\s+(?:\\([^)]*\\)\\s*)?|type\\s+)([A-Z]\\w*)");

    private void readGo(String c) {
        Matcher m = GO_IMPORT_ONE.matcher(c);
        while (m.find()) rawImports.add(m.group(1));
        m = GO_IMPORT_BLOCK.matcher(c);
        while (m.find()) {
            Matcher q = GO_QUOTED.matcher(m.group(1));
            while (q.find()) rawImports.add(q.group(1));
        }
        m = GO_EXPORT.matcher(c);
        while (m.find()) symbols.add(m.group(1));
    }

    // ------------------------------------------------------------------ role

    private void decideRole(String c) {
        String lower = path.toLowerCase(Locale.ROOT);
        String n = name.toLowerCase(Locale.ROOT);
        if (isTest(lower, n)) {
            role = Role.TEST;
            return;
        }
        if (CONFIG_NAMES.contains(n) || n.matches("[\\w.-]+\\.config\\.(js|ts|mjs|cjs)") || n.startsWith("requirements")
                && n.endsWith(".txt") || n.startsWith("dockerfile") || n.startsWith("docker-compose") || n.startsWith(".env")) {
            role = Role.CONFIG;
            return;
        }
        if (language == null) {
            role = Role.DATA;
            return;
        }
        switch (language) {
            case "SQL" -> role = lower.contains("migration") ? Role.MIGRATION : Role.DATA;
            case "CSS" -> role = Role.STYLE;
            case "Markdown", "Text" -> role = Role.DOC;
            case "JSON", "YAML", "TOML", "XML", "Config", "Gradle", "Docker", "Make" -> role = Role.CONFIG;
            case "Shell" -> role = Role.SCRIPT;
            case "TypeScript", "JavaScript" -> jsRole(lower, n, c);
            case "Python" -> role = c.matches("(?s).*if\\s+__name__\\s*==\\s*['\"]__main__['\"].*") ? Role.ENTRY
                    : !endpoints.isEmpty() ? Role.CONTROLLER : n.equals("manage.py") || n.equals("main.py") || n.equals("app.py") ? Role.ENTRY : Role.CODE;
            case "Java", "Kotlin" -> role = c.contains("@SpringBootApplication") || c.contains("static void main(") || c.contains("fun main(")
                    ? Role.ENTRY : c.matches("(?s).*@(RestController|Controller)\\b(?!Advice).*") ? Role.CONTROLLER
                    : c.matches("(?s).*@(Service|Component|Repository|Configuration|ControllerAdvice|RestControllerAdvice)\\b.*") ? Role.SERVICE
                    : Role.CODE;
            case "Go" -> role = c.contains("package main") && c.contains("func main()") ? Role.ENTRY : Role.CODE;
            default -> role = Role.CODE;
        }
        if (lower.startsWith("scripts/") || lower.contains("/scripts/") || lower.startsWith("bin/") || lower.contains("/bin/")) {
            if (role == Role.CODE) role = Role.SCRIPT;
        }
    }

    private static boolean isTest(String lower, String n) {
        return lower.contains("/test/") || lower.startsWith("test/") || lower.contains("/tests/") || lower.startsWith("tests/")
                || lower.contains("__tests__/") || n.matches(".*\\.(test|spec)\\.\\w+$") || n.matches("test_.*\\.py")
                || n.matches(".*_test\\.(go|py)") || n.matches(".*(test|tests|it)\\.(java|kt)") && !n.equals("test.java");
    }

    private void jsRole(String lower, String n, String c) {
        int app = appDirIndex(lower);
        if (app >= 0) {
            String rest = path.substring(app);  // after "app/"
            String base = n.replaceAll("\\.(tsx|ts|jsx|js|mjs)$", "");
            if (base.equals("route")) {
                role = Role.API_ROUTE;
                route = routeOf(rest);
                Matcher m = NEXT_METHODS.matcher(c);
                while (m.find()) endpoints.add((m.group(1) != null ? m.group(1) : m.group(2)) + " " + route);
                return;
            }
            if (base.equals("page") || base.matches("loading|error|not-found|template|default|global-error")) {
                role = Role.PAGE;
                route = routeOf(rest);
                return;
            }
            if (base.equals("layout")) {
                role = Role.LAYOUT;
                route = routeOf(rest);
                return;
            }
        }
        int pages = lower.startsWith("pages/") ? 6 : lower.indexOf("/pages/") >= 0 ? lower.indexOf("/pages/") + 7 : -1;
        if (pages >= 0 && !n.startsWith("_")) {
            String rest = path.substring(pages).replaceAll("\\.(tsx|ts|jsx|js)$", "").replaceAll("(^|/)index$", "");
            route = "/" + rest;
            role = rest.startsWith("api/") || rest.equals("api") ? Role.API_ROUTE : Role.PAGE;
            return;
        }
        if (n.matches("middleware\\.(ts|js)")) {
            role = Role.ENTRY;
            return;
        }
        if (!endpoints.isEmpty()) {
            role = Role.CONTROLLER;
            return;
        }
        if ((n.endsWith(".tsx") || n.endsWith(".jsx")) && symbols.stream().anyMatch(s -> Character.isUpperCase(s.charAt(0)))) {
            role = Role.COMPONENT;
            return;
        }
        if (c.contains("createServer(") || c.contains(".listen(")) role = Role.ENTRY;
    }

    /** Index just past the Next.js "app/" folder in the path, or -1. */
    private static int appDirIndex(String lower) {
        if (lower.startsWith("app/")) return 4;
        if (lower.startsWith("src/app/")) return 8;
        int i = lower.lastIndexOf("/src/app/");
        if (i >= 0) return i + 9;
        i = lower.lastIndexOf("/app/");
        return i >= 0 && !lower.contains("/app/api/") ? i + 5 : lower.contains("/app/api/") ? lower.lastIndexOf("/app/") + 5 : -1;
    }

    /** "(app)/p/[projectId]/recall/page.tsx" → "/p/[projectId]/recall" (route groups removed). */
    static String routeOf(String rest) {
        String dir = rest.contains("/") ? rest.substring(0, rest.lastIndexOf('/')) : "";
        StringBuilder sb = new StringBuilder();
        for (String seg : dir.split("/")) {
            if (seg.isEmpty() || seg.startsWith("(") && seg.endsWith(")") || seg.startsWith("@")) continue;
            sb.append('/').append(seg);
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    // ------------------------------------------------------------------ summary

    private static final Pattern BLOCK_DOC = Pattern.compile("(?s)/\\*\\*(.*?)\\*/");
    private static final Pattern PY_DOC = Pattern.compile("(?s)\\A(?:\\s*#[^\\n]*\\n)*\\s*(?:[rRuU])?(\"\"\"|''')(.*?)\\1");
    private static final Pattern GO_PACKAGE_DOC = Pattern.compile("(?m)^// Package \\w+ (.+)$");

    /** The file's own description: its first doc comment, docstring or Go package comment. */
    private String docSummary(String c) {
        String raw = null;
        if ("Python".equals(language)) {
            Matcher m = PY_DOC.matcher(c);
            if (m.find()) raw = m.group(2);
        } else if ("Go".equals(language)) {
            Matcher m = GO_PACKAGE_DOC.matcher(c);
            if (m.find()) raw = capitalise(m.group(1));
        } else if (language != null && List.of("TypeScript", "JavaScript", "Java", "Kotlin").contains(language)) {
            raw = fileDoc(c);
        }
        if (raw == null) return null;
        // Keep the lead-in, not the list or detail that follows it.
        Matcher block = BLOCK_TAG.matcher(raw);
        if (block.find() && block.start() > 10) raw = raw.substring(0, block.start());
        String text = raw.replaceAll("(?m)^\\s*\\*\\s?", " ")
                .replaceAll("(?m)^\\s*@\\w+.*$", " ")
                .replaceAll("\\{@\\w+\\s+([^}]*)}", "$1")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\s+", " ").strip();
        if (text.length() < 8) return null;
        int end = firstSentenceEnd(text);
        String s = (end > 0 ? text.substring(0, end) : text).replaceAll("[:;,]$", ".");
        return s.length() > 220 ? s.substring(0, 219).strip() + "\u2026" : s;
    }

    private static final Pattern BLOCK_TAG = Pattern.compile("(?i)<(p|ul|ol|li|pre|h\\d|table|dl)\\b");
    private static final Pattern DECL = Pattern.compile(
            "(?:export\\s+)?(?:default\\s+)?(?:(?:public|private|protected|internal|final|abstract|sealed|static|async|data|open|declare)\\s+)*"
                    + "(?:function\\*?|class|interface|record|enum|const|let|var|type|object|@interface)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern ANNOTATIONS = Pattern.compile("(?s)\\A\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*");

    /**
     * The doc comment that describes the file: the one on its main declaration (named like the
     * file, or the default export), or a header comment above the imports. A comment on some
     * other declaration describes that declaration, not the file.
     */
    private String fileDoc(String c) {
        String base = name.replaceAll("\\..*$", "");
        Matcher m = BLOCK_DOC.matcher(c);
        String header = null;
        boolean first = true;
        while (m.find()) {
            String after = c.substring(m.end(), Math.min(c.length(), m.end() + 500));
            String decl = ANNOTATIONS.matcher(after).replaceFirst("").stripLeading();  // past @Service, @RestController…
            Matcher d = DECL.matcher(decl);
            boolean declares = d.lookingAt();
            if (declares && (d.group(1).equalsIgnoreCase(base) || decl.startsWith("export default"))) return m.group(1);
            if (decl.startsWith("export default")) return m.group(1);
            if (first && !declares) {
                String before = c.substring(0, m.start()).replaceAll("(?m)^\\s*(?://.*|package\\s.*|['\"]use \\w+['\"];?)$", "");
                if (before.isBlank()) header = m.group(1);
            }
            first = false;
        }
        return header;
    }

    private static int firstSentenceEnd(String t) {
        for (int i = 0; i < t.length() - 1; i++) {
            if ((t.charAt(i) == '.' || t.charAt(i) == '!' || t.charAt(i) == '?') && t.charAt(i + 1) == ' '
                    && i > 10 && !t.substring(Math.max(0, i - 3), i).matches(".*\\b(e\\.g|i\\.e|vs)")) {
                return i + 1;
            }
        }
        return t.length();
    }

    /** A plain description from what the file is, when it doesn't describe itself. */
    private String generatedSummary() {
        List<String> names = new ArrayList<>(symbols);
        return switch (role) {
            case PAGE -> "Page at " + route + ".";
            case LAYOUT -> "Layout shared by the pages under " + route + ".";
            case API_ROUTE -> endpoints.isEmpty() ? "API route at " + route + "."
                    : "API route at " + route + ": handles " + verbs() + ".";
            case CONTROLLER -> "API controller with " + endpoints.size() + (endpoints.size() == 1 ? " endpoint" : " endpoints")
                    + (endpoints.isEmpty() ? "." : ", such as " + endpoints.get(0) + ".");
            case COMPONENT -> "Component " + names.stream().filter(s -> Character.isUpperCase(s.charAt(0))).findFirst().orElse("") + ".";
            case TEST -> "Tests.";
            case CONFIG -> "Configuration (" + name + ").";
            case MIGRATION -> "Database migration.";
            case STYLE -> "Styles.";
            case DOC -> "Documentation.";
            case ENTRY -> "Entry point" + (names.isEmpty() ? "." : ": " + names.get(0) + ".");
            case SCRIPT -> "Script.";
            default -> names.isEmpty() ? "" : "Defines " + listOf(names) + ".";
        };
    }

    private String verbs() {
        List<String> v = new ArrayList<>();
        for (String e : endpoints) {
            String verb = e.substring(0, e.indexOf(' '));
            if (!v.contains(verb)) v.add(verb);
        }
        return listOf(v);
    }

    static String listOf(List<String> items) {
        List<String> shown = items.size() > 4 ? items.subList(0, 3) : items;
        String more = items.size() > 4 ? " and " + (items.size() - 3) + " more" : "";
        if (shown.size() == 1) return shown.get(0) + more;
        if (!more.isEmpty()) return String.join(", ", shown) + more;
        return String.join(", ", shown.subList(0, shown.size() - 1)) + " and " + shown.get(shown.size() - 1);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Removes // and /* *\/ comments so commented-out imports aren't counted (strings left alone). */
    static String stripJsComments(String c) {
        return c.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)(^|[^:\"'`])//.*$", "$1");
    }
}
