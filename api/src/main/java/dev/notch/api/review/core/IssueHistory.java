package dev.notch.api.review.core;

import dev.notch.api.review.ReviewReport;
import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.search.core.ProjectIndex;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects a review's issues to the project's history: for each issue, the past sessions,
 * commits and chats that mention the same error, function, file or ruled-out tool.
 *
 * <p>The lookups are exact-string searches on the project's suffix array, which is what makes
 * this work: an error code like {@code ECONNREFUSED} or a path like {@code src/db/pool.ts} is
 * matched exactly, wherever it appears, in O(m log n) per term, where meaning-based search
 * would return loosely related passages. Each hit also says how many other sessions the
 * union-find threads link to the same piece of work.
 */
public final class IssueHistory {

    static final int MAX_TERMS = 4;
    static final int MAX_PER_ISSUE = 2;
    static final int EXCERPT = 220;

    /**
     * A past passage about the same thing as an issue.
     *
     * @param term    the exact text that matched, so the person sees why it's linked
     * @param related other conversations the thread groups with this one
     */
    public record Seen(String term, UUID conversationId, String conversationTitle, Instant sentAt, String excerpt,
                       boolean decision, int related) {
    }

    private static final Pattern BACKTICKED = Pattern.compile("`([^`]{3,80})`");
    private static final Pattern ERROR_CODE = Pattern.compile("\\b(E[A-Z]{3,}|[A-Z][A-Z0-9]*_[A-Z0-9_]{2,}|\\w+(?:Error|Exception))\\b");
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]{3,})\\(");
    private static final Pattern IDENT = Pattern.compile("\\b([a-z]+[A-Z][A-Za-z0-9]{2,}|[a-z][a-z0-9]*_[a-z0-9_]{2,})\\b");
    private static final Pattern RULED_OUT_TOOL = Pattern.compile("uses (.+?), which a decision ruled out");
    private static final Pattern GENERIC_NAME = Pattern.compile("(?i)(index|page|layout|route|main|app|utils?|types?|config)\\..*");

    private IssueHistory() {
    }

    /** One list per issue, in the issues' order; empty lists where nothing matched. */
    public static List<List<Seen>> find(ProjectIndex index, List<ReviewReport.Issue> issues) {
        List<List<Seen>> out = new ArrayList<>(issues.size());
        if (index.isEmpty()) {
            for (int i = 0; i < issues.size(); i++) out.add(List.of());
            return out;
        }
        Map<UUID, Integer> threadSize = new HashMap<>();
        for (List<UUID> thread : index.linkedThreads()) for (UUID c : thread) threadSize.put(c, thread.size());
        Map<String, List<IndexedChunk>> cache = new HashMap<>();
        for (ReviewReport.Issue issue : issues) {
            Map<UUID, Seen> byConversation = new LinkedHashMap<>();
            for (String term : terms(issue)) {
                List<IndexedChunk> hits = cache.computeIfAbsent(term.toLowerCase(Locale.ROOT), t -> index.chunksContaining(term));
                for (IndexedChunk c : ordered(hits, "decision".equals(issue.category()))) {
                    if (byConversation.containsKey(c.conversationId())) continue;
                    byConversation.put(c.conversationId(), new Seen(term, c.conversationId(), c.conversationTitle(), c.sentAt(),
                            isCommand(c) && !mentionedOutsideCommand(c.text(), term)
                                    ? "Claude Code opened or ran this in the session; it wasn't discussed in words."
                                    : excerpt(c.text(), term), c.decision(), Math.max(0, threadSize.getOrDefault(c.conversationId(), 1) - 1)));
                    if (byConversation.size() == MAX_PER_ISSUE) break;
                }
                if (byConversation.size() == MAX_PER_ISSUE) break;
            }
            out.add(List.copyOf(byConversation.values()));
        }
        return out;
    }

    /**
     * What to search for, most specific first: names in backticks, error codes, function calls
     * and identifiers from the title, the tool a decision ruled out, then the file's path and,
     * if it's distinctive, its name. Plain words are never searched: they'd match everything.
     */
    static List<String> terms(ReviewReport.Issue issue) {
        Set<String> out = new LinkedHashSet<>();
        String text = issue.title() + " " + (issue.detail() == null ? "" : issue.detail());
        Matcher tool = RULED_OUT_TOOL.matcher(issue.title());
        if ("decision".equals(issue.category()) && tool.find()) out.add(tool.group(1));
        for (Pattern p : List.of(BACKTICKED, ERROR_CODE, CALL, IDENT)) {
            Matcher m = p.matcher(text);
            while (m.find() && out.size() < MAX_TERMS) out.add(m.group(1));
        }
        if (issue.file() != null) {
            out.add(issue.file());
            String name = issue.file().substring(issue.file().lastIndexOf('/') + 1);
            if (name.length() >= 6 && name.contains(".") && !GENERIC_NAME.matcher(name).matches()) out.add(name);
        }
        List<String> list = new ArrayList<>(out);
        return list.size() > MAX_TERMS + 2 ? list.subList(0, MAX_TERMS + 2) : list;
    }

    /** Newest first; for an issue about a decision, passages that record a decision come first. */
    private static List<IndexedChunk> ordered(List<IndexedChunk> hits, boolean decisionsFirst) {
        List<IndexedChunk> sorted = new ArrayList<>(hits);
        sorted.sort((a, b) -> {
            if (decisionsFirst && a.decision() != b.decision()) return a.decision() ? -1 : 1;
            // A command the agent ran (cat, sed...) mentions the file but says nothing about it.
            boolean ac = isCommand(a), bc = isCommand(b);
            if (ac != bc) return ac ? 1 : -1;
            return b.sentAt().compareTo(a.sentAt());
        });
        return sorted;
    }

    /** Whether the term appears in a line that isn't a command the agent ran. */
    static boolean mentionedOutsideCommand(String text, String term) {
        String t = term.toLowerCase(Locale.ROOT);
        for (String line : text.split("\n")) {
            if (!line.contains("Ran: ") && line.toLowerCase(Locale.ROOT).contains(t)) return true;
        }
        return false;
    }

    static boolean isCommand(IndexedChunk c) {
        return c.text().contains("Ran: ");
    }

    private static final Pattern COMMIT =Pattern.compile("Commit ([0-9a-f]{7,40}): (.+?)(?:\\s+Changed:|$)", Pattern.DOTALL);

    static String excerpt(String text, String term) {
        // A captured commit lists every file it changed; its message says far more than the list.
        Matcher commit = COMMIT.matcher(text);
        if (commit.find()) {
            String subject = commit.group(2).replaceAll("\\s+", " ").strip();
            if (subject.length() > EXCERPT) subject = subject.substring(0, EXCERPT - 1) + "…";
            return "Changed in commit " + commit.group(1).substring(0, 7) + ": " + subject;
        }
        int at = text.toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT));
        int start = Math.max(0, at - EXCERPT / 2);
        int end = Math.min(text.length(), Math.max(at, 0) + term.length() + EXCERPT / 2);
        String s = text.substring(start, end).replaceAll("\\s+", " ").strip();
        return (start > 0 ? "…" : "") + s + (end < text.length() ? "…" : "");
    }
}
