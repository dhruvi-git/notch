package dev.notch.guard;

import dev.notch.engine.text.Kmp;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the lines a change adds against active decisions.
 *
 * <p>For every tool a decision ruled out, {@link Kmp} scans each added line for each of the
 * tool's aliases ("mongo", "mongoose", "pymongo", ...), keeping whole-word matches only. Where
 * the line sits decides how much it counts:
 * <ul>
 *   <li><b>Strong</b>: dependency manifests, imports, connection strings. A ruled-out tool here
 *       is HIGH; a different tool of the same kind as the decision's choice is MEDIUM.</li>
 *   <li><b>Code</b>: other lines. A ruled-out tool is MEDIUM; short aliases such as "pg" are
 *       ignored here, because they appear in ordinary code too often.</li>
 *   <li><b>Ignored</b>: comments, documentation, lockfiles, and Notch's own agent files, which
 *       list ruled-out tools on purpose.</li>
 * </ul>
 */
public final class DiffGuard {

    static final int MAX_FINDINGS = 50;
    static final int SHORT_ALIAS = 3;

    enum Signal { STRONG, CODE, IGNORED }

    private static final Set<String> MANIFESTS = Set.of(
            "package.json", "requirements.txt", "requirements-dev.txt", "pyproject.toml", "pipfile", "setup.py",
            "setup.cfg", "pom.xml", "build.gradle", "build.gradle.kts", "go.mod", "cargo.toml", "gemfile",
            "composer.json", "dockerfile", "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml",
            "environment.yml", "deno.json");
    private static final Set<String> SKIPPED_FILES = Set.of(
            "agents.md", "claude.md", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock",
            "pipfile.lock", "cargo.lock", "go.sum", "gemfile.lock", "composer.lock", "bun.lockb", "uv.lock");
    private static final Set<String> DOC_EXTENSIONS = Set.of("md", "markdown", "txt", "rst", "adoc", "mdx");
    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*(?:import\\s|from\\s+\\S+\\s+import\\s|export\\s+.*\\sfrom\\s|using\\s|#include\\s|use\\s|require\\s)"
                    + "|\\brequire\\s*\\(|\\bimport\\s*\\(|\\bimport\\s+[\\w{*,\\s}]+\\s+from\\s");
    private static final Pattern COMMENT = Pattern.compile("^\\s*(?://|#(?!include)|/\\*|\\*|--|<!--|;)");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    public List<GuardFinding> check(List<Decision> decisions, List<FileChange> changes) {
        List<GuardFinding> out = collect(decisions, changes);
        out.sort((a, b) -> a.severity() != b.severity() ? a.severity().compareTo(b.severity())
                : a.location().compareTo(b.location()));
        return out;
    }

    private List<GuardFinding> collect(List<Decision> decisions, List<FileChange> changes) {
        List<GuardFinding> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();  // one finding per decision, file and tool
        for (Decision d : decisions) {
            DecisionFacts facts = DecisionFacts.of(d.title(), d.rejectedOptions());
            List<TechCatalog.Tech> sameKind = facts.category() == null ? List.of()
                    : TechCatalog.inCategory(facts.category()).stream()
                    .filter(t -> !facts.chosen().contains(t) && !facts.rejected().contains(t)).toList();
            if (facts.rejected().isEmpty() && sameKind.isEmpty()) continue;
            for (FileChange change : changes) {
                Signal fileSignal = fileSignal(change.path());
                if (fileSignal == Signal.IGNORED) continue;
                for (FileChange.Line line : change.added()) {
                    Signal signal = lineSignal(fileSignal, line.text());
                    if (signal == Signal.IGNORED) continue;
                    for (TechCatalog.Tech t : facts.rejected()) {
                        String alias = firstAlias(line.text(), t, signal);
                        if (alias == null || !seen.add(d.id() + "|" + change.path() + "|" + t.name())) continue;
                        out.add(ruledOut(d, facts, change.path(), line, t, alias, signal));
                    }
                    if (signal != Signal.STRONG) continue;
                    for (TechCatalog.Tech t : sameKind) {
                        String alias = firstAlias(line.text(), t, signal);
                        if (alias == null || !seen.add(d.id() + "|" + change.path() + "|" + t.name())) continue;
                        out.add(differentChoice(d, facts, change.path(), line, t, alias));
                    }
                    if (out.size() >= MAX_FINDINGS) return out;
                }
            }
        }
        return out;
    }

    private static GuardFinding ruledOut(Decision d, DecisionFacts f, String path, FileChange.Line line,
                                         TechCatalog.Tech t, String alias, Signal signal) {
        String what = alias.equalsIgnoreCase(t.name()) ? t.name() : t.name() + " (" + alias + ")";
        String chosen = f.chosen().isEmpty() ? "" : " chose " + f.chosen().get(0).name() + " and";
        String message = "Adds " + what + ", but on " + day(d) + " the project" + chosen + " ruled it out: \u201c"
                + shorten(d.title()) + "\u201d";
        return new GuardFinding(signal == Signal.STRONG ? GuardFinding.Severity.HIGH : GuardFinding.Severity.MEDIUM,
                GuardFinding.Kind.RULED_OUT, path, line.number(), line.text().strip(), t.name(), alias, d.id(),
                d.title(), d.decidedAt(), message);
    }

    private static GuardFinding differentChoice(Decision d, DecisionFacts f, String path, FileChange.Line line,
                                                TechCatalog.Tech t, String alias) {
        String message = "Adds " + t.name() + ", but the project's " + f.category() + " decision is "
                + f.chosen().get(0).name() + " (" + day(d) + "): \u201c" + shorten(d.title()) + "\u201d";
        return new GuardFinding(GuardFinding.Severity.MEDIUM, GuardFinding.Kind.DIFFERENT_CHOICE, path,
                line.number(), line.text().strip(), t.name(), alias, d.id(), d.title(), d.decidedAt(), message);
    }

    static Signal fileSignal(String path) {
        String p = path.replace('\\', '/');
        String name = p.substring(p.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (SKIPPED_FILES.contains(name) || p.startsWith(".notch") || p.contains("/.notch")) return Signal.IGNORED;
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        if (DOC_EXTENSIONS.contains(ext) && !name.startsWith("requirements")) return Signal.IGNORED;
        if (MANIFESTS.contains(name) || name.startsWith("requirements") || name.startsWith("dockerfile")
                || name.startsWith("docker-compose")) {
            return Signal.STRONG;
        }
        return Signal.CODE;
    }

    static Signal lineSignal(Signal file, String text) {
        if (text.isBlank()) return Signal.IGNORED;
        if (file == Signal.STRONG) return COMMENT.matcher(text).find() && !text.strip().startsWith("#include")
                ? Signal.IGNORED : Signal.STRONG;
        if (COMMENT.matcher(text).find()) return Signal.IGNORED;
        if (IMPORT.matcher(text).find() || text.contains("://")) return Signal.STRONG;
        return Signal.CODE;
    }

    /** The first alias of {@code tech} in {@code text} as a whole word, found with KMP; null if none. */
    static String firstAlias(String text, TechCatalog.Tech tech, Signal signal) {
        for (String alias : TechCatalog.aliases(tech)) {
            if (alias.length() <= SHORT_ALIAS && signal != Signal.STRONG) continue;
            for (int at : Kmp.findAll(text, alias, true)) {
                int end = at + alias.length();
                if (boundary(text, at - 1) && boundary(text, end)) return alias;
            }
        }
        return null;
    }

    /** Word edges: start or end of text, or any character that can't be part of a package name. */
    private static boolean boundary(String text, int i) {
        if (i < 0 || i >= text.length()) return true;
        char c = text.charAt(i);
        return !(Character.isLetterOrDigit(c) || c == '_' || c == '-');
    }

    private static String day(Decision d) {
        return d.decidedAt() == null ? "an earlier date" : DAY.format(d.decidedAt());
    }

    private static String shorten(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= 110 ? t : t.substring(0, 109) + "\u2026";
    }
}
