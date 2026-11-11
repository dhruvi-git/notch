package dev.notch.api.explain.core;

import dev.notch.explain.CodeMap;
import dev.notch.guard.DecisionFacts;
import dev.notch.guard.TechCatalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Connects code to the decisions behind it, through the tools files import: a file importing
 * {@code pg} follows "we chose Postgres"; a file importing {@code mongoose} goes against it.
 */
public final class CodeDecisionLinks {

    public record Decision(String id, String title, List<String> rejectedOptions, Instant decidedAt) {
    }

    public enum Kind { FOLLOWS, GOES_AGAINST }

    public record Link(Kind kind, String tool, String decisionId, String decisionTitle, Instant decidedAt) {
    }

    /** A file that uses a tool a decision ruled out. */
    public record Conflict(String path, String tool, String decisionId, String decisionTitle, Instant decidedAt) {
    }

    /** How the codebase uses one tool, and what the project decided about it. */
    public record ToolUse(String tool, int files, String decisionId, String decisionTitle, Kind kind) {
    }

    private CodeDecisionLinks() {
    }

    /** The decisions a file follows or goes against. */
    public static List<Link> forFile(CodeMap.FileInfo file, List<Decision> decisions) {
        List<Link> out = new ArrayList<>();
        Set<String> uses = new HashSet<>(file.tools());
        Map<String, Instant> latestChoice = latestChoices(decisions);
        for (Decision d : decisions) {
            DecisionFacts f = DecisionFacts.of(d.title(), d.rejectedOptions());
            for (TechCatalog.Tech t : f.chosen()) {
                if (uses.contains(t.name())) out.add(new Link(Kind.FOLLOWS, t.name(), d.id(), d.title(), d.decidedAt()));
            }
            for (TechCatalog.Tech t : f.rejected()) {
                if (uses.contains(t.name()) && !chosenLater(latestChoice, t.name(), d)) {
                    out.add(new Link(Kind.GOES_AGAINST, t.name(), d.id(), d.title(), d.decidedAt()));
                }
            }
        }
        out.sort(Comparator.comparing(Link::kind).reversed().thenComparing(Link::tool));
        return out;
    }

    /** Every file using a ruled-out tool, across the codebase. */
    public static List<Conflict> conflicts(CodeMap map, List<Decision> decisions) {
        List<Conflict> out = new ArrayList<>();
        for (CodeMap.FileInfo f : map.files()) {
            if (f.tools().isEmpty()) continue;
            for (Link l : forFile(f, decisions)) {
                if (l.kind() == Kind.GOES_AGAINST) out.add(new Conflict(f.path(), l.tool(), l.decisionId(), l.decisionTitle(), l.decidedAt()));
            }
        }
        return out;
    }

    /** Each tool the code uses, how many files use it, and the decision that covers it, if any. */
    public static List<ToolUse> toolUsage(CodeMap map, List<Decision> decisions) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CodeMap.FileInfo f : map.files()) for (String t : f.tools()) counts.merge(t, 1, Integer::sum);
        Map<String, Instant> latestChoice = latestChoices(decisions);
        List<ToolUse> out = new ArrayList<>();
        counts.forEach((tool, n) -> {
            Decision covering = null;
            Kind kind = null;
            for (Decision d : decisions) {
                DecisionFacts f = DecisionFacts.of(d.title(), d.rejectedOptions());
                if (f.chosen().stream().anyMatch(t -> t.name().equals(tool))) {
                    covering = d;
                    kind = Kind.FOLLOWS;
                    break;
                }
                if (f.rejected().stream().anyMatch(t -> t.name().equals(tool)) && !chosenLater(latestChoice, tool, d)) {
                    covering = d;
                    kind = Kind.GOES_AGAINST;
                }
            }
            out.add(new ToolUse(tool, n, covering == null ? null : covering.id(), covering == null ? null : covering.title(), kind));
        });
        out.sort(Comparator.comparingInt(ToolUse::files).reversed().thenComparing(ToolUse::tool));
        return out;
    }

    private static Map<String, Instant> latestChoices(List<Decision> decisions) {
        Map<String, Instant> m = new java.util.HashMap<>();
        for (Decision d : decisions) {
            if (d.decidedAt() == null) continue;
            for (TechCatalog.Tech t : DecisionFacts.of(d.title(), d.rejectedOptions()).chosen()) {
                m.merge(t.name(), d.decidedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        return m;
    }

    /** As in the guard: a later decision choosing the tool lifts an earlier ruling-out. */
    private static boolean chosenLater(Map<String, Instant> latest, String tool, Decision d) {
        Instant chosen = latest.get(tool);
        return chosen != null && d.decidedAt() != null && chosen.isAfter(d.decidedAt());
    }
}
