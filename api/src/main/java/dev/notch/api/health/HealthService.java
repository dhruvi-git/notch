package dev.notch.api.health;

import dev.notch.api.decisions.DriftService;
import dev.notch.api.explain.CodeMapService;
import dev.notch.api.explain.core.CodeDecisionLinks;
import dev.notch.api.health.core.WorkTrail;
import dev.notch.api.memory.MemoryCodec;
import dev.notch.api.memory.MemoryPayload;
import dev.notch.api.memory.MemoryRepository;
import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectService;
import dev.notch.api.review.ReviewRepository;
import dev.notch.api.search.IndexRegistry;
import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.search.core.ProjectIndex;
import dev.notch.api.security.CurrentUser;
import dev.notch.engine.select.RandomizedSelect;
import dev.notch.explain.CodeMap;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The project's health check: the four ways projects built with AI agents fall apart, each
 * measured from what Notch already holds, with its evidence and one thing to do about it; and a
 * recap of what's done, what isn't, and what's been forgotten, for coming back to a project.
 *
 * <ul>
 *   <li><b>Understanding</b>: files agents edited that the developer never mentioned in their own
 *   messages. Each candidate is checked with an exact search on the suffix array, restricted to
 *   the developer's messages.</li>
 *   <li><b>Decisions</b>: pivots (replaced decisions), code still using what a decision ruled out,
 *   and drift alerts.</li>
 *   <li><b>Spread</b>: work streams (union-find threads) and files changed by many separate
 *   sessions, where approaches collide.</li>
 *   <li><b>Unfinished</b>: streams that went quiet with open tasks, and tasks left for weeks.</li>
 *   <li><b>Quality</b>: the latest review's serious issues.</li>
 * </ul>
 * The longest lists are cut to the most important entries with Quickselect.
 */
@Service
public class HealthService {

    static final Duration QUIET = Duration.ofDays(7);
    static final Duration FORGOTTEN = Duration.ofDays(14);
    static final Duration RECENT = Duration.ofDays(21);
    static final int SHOWN = 5;

    /** {@code link} is a page under the project ("explain/file?path=...", "ledger"), or null. */
    public record Evidence(String text, String detail, String link) {
    }

    /** {@code level}: "ok", "watch", "risk", or "none" when there isn't enough to judge yet. */
    public record Risk(String key, String title, String level, String headline, List<Evidence> evidence,
                       String action, String actionLink) {
    }

    public record Item(String text, Instant when, String detail) {
    }

    public record Recap(List<Item> done, List<Item> open, List<Item> forgotten) {
    }

    public record Health(List<Risk> risks, Recap recap, Instant computedAt) {
    }

    private final ProjectService projects;
    private final CurrentUser currentUser;
    private final IndexRegistry indexes;
    private final CodeMapService codeMaps;
    private final MemoryRepository memories;
    private final MemoryCodec codec;
    private final ReviewRepository reviews;
    private final DriftService drift;
    private final RandomizedSelect select = new RandomizedSelect(11);

    public HealthService(ProjectService projects, CurrentUser currentUser, IndexRegistry indexes, CodeMapService codeMaps,
                         MemoryRepository memories, MemoryCodec codec, ReviewRepository reviews, DriftService drift) {
        this.projects = projects;
        this.currentUser = currentUser;
        this.indexes = indexes;
        this.codeMaps = codeMaps;
        this.memories = memories;
        this.codec = codec;
        this.reviews = reviews;
        this.drift = drift;
    }

    public Health check(UUID projectId) {
        Project project = projects.require(projectId);
        UUID userId = currentUser.id();
        Instant now = Instant.now();
        ProjectIndex index = indexes.get(project);
        CodeMap map = codeMaps.latestMap(project).orElse(null);
        List<IndexedChunk> chunks = index.chunksSince(Instant.EPOCH);

        List<MemoryRepository.StoredMemory> decisionsAll = memories.list(project.id(), userId, "DECISION", null, 500);
        List<MemoryRepository.StoredMemory> openTasks = memories.list(project.id(), userId, "TASK", "ACTIVE", 500);
        List<MemoryRepository.StoredMemory> doneTasks = memories.list(project.id(), userId, "TASK", "DONE", 50);

        // Which sessions edited which files, and when each conversation was last active.
        Map<String, Set<UUID>> editedBy = new LinkedHashMap<>();
        Map<UUID, Instant> lastActive = new HashMap<>();
        Map<UUID, String> titles = new HashMap<>();
        List<WorkTrail.Commit> commits = new ArrayList<>();
        Map<WorkTrail.Commit, Instant> commitTimes = new HashMap<>();
        for (IndexedChunk c : chunks) {
            lastActive.merge(c.conversationId(), c.sentAt(), (a, b) -> a.isAfter(b) ? a : b);
            titles.putIfAbsent(c.conversationId(), c.conversationTitle());
            for (String p : WorkTrail.editedPaths(c.text())) {
                String path = map == null ? p : WorkTrail.toMapPath(p, map);
                if (path != null) editedBy.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(c.conversationId());
            }
            WorkTrail.Commit commit = WorkTrail.commit(c.text());
            if (commit != null && commitTimes.putIfAbsent(commit, c.sentAt()) == null) commits.add(commit);
        }

        List<Risk> risks = new ArrayList<>();
        risks.add(understanding(index, map, editedBy));
        risks.add(decisions(project, userId, map, decisionsAll));
        risks.add(spread(index, editedBy, titles, lastActive, now));
        List<Item> abandoned = abandonedStreams(index, openTasks, lastActive, titles, now);
        risks.add(unfinished(openTasks, abandoned, now));
        risks.add(quality(project, userId));

        List<Item> done = new ArrayList<>();
        for (MemoryRepository.StoredMemory t : doneTasks.subList(0, Math.min(SHOWN, doneTasks.size()))) {
            done.add(new Item(codec.open(t.payload(), userId).title(), t.createdAt(), "Task finished"));
        }
        commits.sort(Comparator.comparing((WorkTrail.Commit c) -> commitTimes.get(c)).reversed());
        for (WorkTrail.Commit c : commits) {
            if (done.size() >= SHOWN * 2 || commitTimes.get(c).isBefore(now.minus(RECENT))) break;
            done.add(new Item(c.subject(), commitTimes.get(c), "Commit " + c.sha()));
        }
        List<Item> open = new ArrayList<>();
        List<Item> forgotten = new ArrayList<>(abandoned);
        for (MemoryRepository.StoredMemory t : openTasks) {
            Item item = new Item(codec.open(t.payload(), userId).title(), t.createdAt(),
                    t.conversationTitle() == null ? null : "From " + t.conversationTitle());
            if (t.createdAt().isBefore(now.minus(FORGOTTEN))) forgotten.add(item);
            else if (open.size() < SHOWN * 2) open.add(item);
        }
        return new Health(risks, new Recap(done, open, forgotten.subList(0, Math.min(SHOWN * 2, forgotten.size()))), now);
    }

    // ------------------------------------------------------------------ the four risks, and quality

    private Risk understanding(ProjectIndex index, CodeMap map, Map<String, Set<UUID>> editedBy) {
        if (editedBy.isEmpty()) {
            return new Risk("understanding", "Do you know what's in your code?", "none",
                    "No agent edits captured yet. Connect the repository and Claude Code sessions show which files agents wrote.",
                    List.of(), "Connect repo", "sync");
        }
        List<String> unseen = new ArrayList<>();
        for (String path : editedBy.keySet()) if (!mentionedByYou(index, path)) unseen.add(path);
        int unseenLines = 0;
        int totalLines = 0;
        if (map != null) {
            for (CodeMap.FileInfo f : map.files()) totalLines += f.lines();
            for (String p : unseen) {
                CodeMap.FileInfo f = map.file(p);
                if (f != null) unseenLines += f.lines();
            }
        }
        double share = totalLines == 0 ? (double) unseen.size() / editedBy.size() : (double) unseenLines / totalLines;
        String level = unseen.isEmpty() ? "ok" : share >= 0.3 ? "risk" : share >= 0.1 || unseen.size() >= 5 ? "watch" : "ok";
        String headline = unseen.isEmpty()
                ? "You've talked about every file agents changed."
                : String.format(Locale.ENGLISH, "%d of %d files agents changed were never mentioned by you%s.", unseen.size(),
                editedBy.size(), totalLines > 0 ? String.format(Locale.ENGLISH, ", about %d%% of the code", Math.round(share * 100)) : "");
        // The most depended-on first: those are the ones that hurt most not to understand.
        List<String> top = unseen.isEmpty() ? List.of() : select.topK(unseen, Math.min(SHOWN, unseen.size()),
                Comparator.comparingInt((String p) -> map == null || map.file(p) == null ? 0 : map.file(p).importedBy().size())
                        .thenComparingInt(p -> map == null || map.file(p) == null ? 0 : map.file(p).lines()));
        List<Evidence> evidence = new ArrayList<>();
        for (String p : top) {
            CodeMap.FileInfo f = map == null ? null : map.file(p);
            String detail = f == null ? "Edited by an agent" : "Edited by an agent; " + (f.importedBy().isEmpty()
                    ? f.lines() + " lines" : "used by " + f.importedBy().size() + (f.importedBy().size() == 1 ? " file" : " files"));
            evidence.add(new Evidence(p, detail, map != null && f != null ? "explain/file?path=" + enc(p) : null));
        }
        return new Risk("understanding", "Do you know what's in your code?", level, headline, evidence,
                "Walk through these in Explain", map == null ? "imports" : "explain");
    }

    /** Whether the developer named the file in one of their own messages: an exact suffix-array search. */
    private static boolean mentionedByYou(ProjectIndex index, String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        List<String> terms = name.length() >= 6 && name.contains(".") ? List.of(path, name) : List.of(path);
        for (String t : terms) {
            for (IndexedChunk c : index.chunksContaining(t)) if ("user".equals(c.role())) return true;
        }
        return false;
    }

    private Risk decisions(Project project, UUID userId, CodeMap map, List<MemoryRepository.StoredMemory> all) {
        Map<UUID, MemoryRepository.StoredMemory> byId = new HashMap<>();
        all.forEach(m -> byId.put(m.id(), m));
        List<Evidence> evidence = new ArrayList<>();
        int pivots = 0;
        for (MemoryRepository.StoredMemory m : all) {
            if (!"SUPERSEDED".equals(m.status())) continue;
            pivots++;
            if (evidence.size() >= 2) continue;
            MemoryPayload old = codec.open(m.payload(), userId);
            MemoryRepository.StoredMemory next = m.supersededBy() == null ? null : byId.get(m.supersededBy());
            MemoryPayload now = next == null ? null : codec.open(next.payload(), userId);
            evidence.add(new Evidence("Pivot: " + (now == null ? "replaced" : now.title()),
                    "Was: " + old.title() + (now != null && now.rationale() != null ? ". Why: " + now.rationale() : ""), "ledger"));
        }
        int leftovers = 0;
        if (map != null) {
            List<CodeDecisionLinks.Conflict> conflicts = CodeDecisionLinks.conflicts(map, codeMaps.decisions(project));
            leftovers = conflicts.size();
            for (CodeDecisionLinks.Conflict c : conflicts.subList(0, Math.min(3, conflicts.size()))) {
                evidence.add(new Evidence(c.path() + " still uses " + c.tool(), "Against: " + c.decisionTitle(),
                        "explain/file?path=" + enc(c.path())));
            }
        }
        int alerts;
        try {
            alerts = drift.alerts(project.id()).size();
        } catch (RuntimeException e) {
            alerts = 0;
        }
        int active = (int) all.stream().filter(m -> "ACTIVE".equals(m.status())).count();
        if (active == 0 && pivots == 0) {
            return new Risk("decisions", "Do you remember why it's built this way?", "none",
                    "No decisions recorded yet. They're collected from your sessions, commits and chats.", List.of(),
                    "Open the Ledger", "ledger");
        }
        int problems = leftovers + alerts;
        String level = problems >= 3 ? "risk" : problems > 0 ? "watch" : "ok";
        String headline = String.format(Locale.ENGLISH, "%d decision%s in force, %d pivot%s.%s", active, active == 1 ? "" : "s",
                pivots, pivots == 1 ? "" : "s", problems == 0 ? " The code and chats follow them."
                        : " " + problems + (problems == 1 ? " place goes" : " places go") + " against them.");
        return new Risk("decisions", "Do you remember why it's built this way?", level, headline, evidence,
                problems > 0 ? "Settle what goes against a decision" : "Open the Ledger", problems > 0 ? "drift" : "ledger");
    }

    private Risk spread(ProjectIndex index, Map<String, Set<UUID>> editedBy, Map<UUID, String> titles,
                        Map<UUID, Instant> lastActive, Instant now) {
        Set<UUID> activeStreams = new LinkedHashSet<>();
        lastActive.forEach((conv, at) -> {
            if (at.isAfter(now.minus(FORGOTTEN))) activeStreams.add(index.threadOf(conv));
        });
        List<Map.Entry<String, Set<UUID>>> churned = editedBy.entrySet().stream().filter(e -> e.getValue().size() >= 3).toList();
        if (editedBy.isEmpty() && activeStreams.isEmpty()) {
            return new Risk("spread", "Are your agents pulling in one direction?", "none",
                    "Nothing captured yet to compare.", List.of(), "Connect repo", "sync");
        }
        List<Map.Entry<String, Set<UUID>>> top = churned.isEmpty() ? List.of() : select.topK(churned,
                Math.min(SHOWN, churned.size()), Comparator.comparingInt(e -> e.getValue().size()));
        List<Evidence> evidence = new ArrayList<>();
        for (Map.Entry<String, Set<UUID>> e : top) {
            List<String> names = e.getValue().stream().map(titles::get).filter(t -> t != null).distinct().limit(3).toList();
            evidence.add(new Evidence(e.getKey(), "Changed in " + e.getValue().size() + " separate sessions: " + String.join("; ", names),
                    "explain/file?path=" + enc(e.getKey())));
        }
        String level = churned.size() >= 3 || activeStreams.size() >= 5 ? "risk" : !churned.isEmpty() || activeStreams.size() >= 3 ? "watch" : "ok";
        String headline = String.format(Locale.ENGLISH, "%d work stream%s active in the last two weeks%s", activeStreams.size(),
                activeStreams.size() == 1 ? "" : "s", churned.isEmpty() ? "; no file is being pulled between sessions."
                        : "; " + churned.size() + (churned.size() == 1 ? " file was" : " files were") + " changed by 3 or more separate sessions.");
        return new Risk("spread", "Are your agents pulling in one direction?", level, headline, evidence,
                churned.isEmpty() ? "See the conversations" : "Check these files agree with your decisions",
                churned.isEmpty() ? "conversations" : "review");
    }

    /** Work streams (union-find threads) that went quiet while tasks from them were still open. */
    private List<Item> abandonedStreams(ProjectIndex index, List<MemoryRepository.StoredMemory> openTasks,
                                        Map<UUID, Instant> lastActive, Map<UUID, String> titles, Instant now) {
        Map<UUID, Instant> streamLast = new HashMap<>();
        Map<UUID, String> streamTitle = new HashMap<>();
        lastActive.forEach((conv, at) -> {
            UUID s = index.threadOf(conv);
            if (streamLast.merge(s, at, (a, b) -> a.isAfter(b) ? a : b) == at) streamTitle.put(s, titles.get(conv));
        });
        Map<UUID, Integer> tasksPerStream = new HashMap<>();
        for (MemoryRepository.StoredMemory t : openTasks) {
            if (t.conversationId() != null && lastActive.containsKey(t.conversationId())) {
                tasksPerStream.merge(index.threadOf(t.conversationId()), 1, Integer::sum);
            }
        }
        List<Item> out = new ArrayList<>();
        tasksPerStream.forEach((s, n) -> {
            Instant last = streamLast.get(s);
            if (last != null && last.isBefore(now.minus(QUIET))) {
                out.add(new Item(streamTitle.getOrDefault(s, "A work stream") + " went quiet", last,
                        n + (n == 1 ? " open task" : " open tasks") + " left in it"));
            }
        });
        out.sort(Comparator.comparing(Item::when));
        return out;
    }

    private Risk unfinished(List<MemoryRepository.StoredMemory> openTasks, List<Item> abandoned, Instant now) {
        long old = openTasks.stream().filter(t -> t.createdAt().isBefore(now.minus(FORGOTTEN))).count();
        List<Evidence> evidence = new ArrayList<>();
        for (Item a : abandoned.subList(0, Math.min(3, abandoned.size()))) evidence.add(new Evidence(a.text(), a.detail(), "resume"));
        String level = abandoned.size() >= 2 || old >= 3 ? "risk" : !abandoned.isEmpty() || old > 0 ? "watch" : "ok";
        String headline = openTasks.isEmpty() ? "No open tasks."
                : String.format(Locale.ENGLISH, "%d open task%s%s%s.", openTasks.size(), openTasks.size() == 1 ? "" : "s",
                old > 0 ? ", " + old + " left for over two weeks" : "",
                abandoned.isEmpty() ? "" : ", " + abandoned.size() + (abandoned.size() == 1 ? " stream" : " streams") + " gone quiet");
        return new Risk("unfinished", "What's done, and what isn't?", level, headline, evidence, "See where you stopped", "resume");
    }

    private Risk quality(Project project, UUID userId) {
        var latest = reviews.list(project.id(), userId).stream().filter(r -> "DONE".equals(r.status())).findFirst();
        if (latest.isEmpty()) {
            return new Risk("quality", "Is the code sound?", "none", "No review yet.", List.of(), "Review your code", "imports");
        }
        var r = latest.get();
        String level = r.highIssues() > 0 ? "risk" : r.issues() >= 3 ? "watch" : "ok";
        String headline = String.format(Locale.ENGLISH, "The latest review found %d issue%s%s.", r.issues(), r.issues() == 1 ? "" : "s",
                r.highIssues() > 0 ? ", " + r.highIssues() + " serious" : "");
        return new Risk("quality", "Is the code sound?", level, headline, List.of(), "Open the review", "review?r=" + r.id());
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
