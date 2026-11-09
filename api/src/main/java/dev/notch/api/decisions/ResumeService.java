package dev.notch.api.decisions;

import dev.notch.api.crypto.ContentCipher;
import dev.notch.api.decisions.core.ContextPackBuilder;
import dev.notch.api.decisions.core.DecisionItem;
import dev.notch.api.decisions.core.TaskPrioritizer;
import dev.notch.api.llm.MemoryKind;
import dev.notch.api.memory.MemoryCodec;
import dev.notch.api.memory.MemoryPayload;
import dev.notch.api.memory.MemoryRepository;
import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectService;
import dev.notch.api.security.CurrentUser;
import dev.notch.api.support.Db;
import dev.notch.engine.select.RandomizedSelect;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "Where you stopped" for a project, and the same state written as a context pack or an
 * {@code AGENTS.md} / {@code CLAUDE.md} file.
 */
@Service
public class ResumeService {

    static final int TOP_TASKS = 5;
    static final int RECENT_DECISIONS = 10;
    static final int RECENT_FIXES = 5;
    static final int QUOTE_CHARS = 220;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    public record LastSession(UUID conversationId, String title, Instant lastActivity, int messages) {
    }

    public record TaskView(UUID id, String title, Instant createdAt, double priority) {
    }

    public record Resume(LastSession lastSession, List<String> whereYouStopped, List<TaskView> openTasks,
                         List<MemoryView> decisions, List<MemoryView> fixes, int driftAlerts) {
    }

    private final JdbcClient jdbc;
    private final ContentCipher cipher;
    private final MemoryRepository memories;
    private final MemoryCodec codec;
    private final ProjectService projects;
    private final CurrentUser currentUser;
    private final DriftService drift;
    private final TaskPrioritizer prioritizer = new TaskPrioritizer(new RandomizedSelect());
    private final ContextPackBuilder packs = new ContextPackBuilder();

    public ResumeService(JdbcClient jdbc, ContentCipher cipher, MemoryRepository memories, MemoryCodec codec,
                         ProjectService projects, CurrentUser currentUser, DriftService drift) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.memories = memories;
        this.codec = codec;
        this.projects = projects;
        this.currentUser = currentUser;
        this.drift = drift;
    }

    public Resume resume(UUID projectId) {
        Project project = projects.require(projectId);
        UUID userId = currentUser.id();
        Instant now = Instant.now();
        LastSession last = lastSession(project, userId);
        List<String> where = whereYouStopped(last, userId);
        List<TaskView> tasks = topTasks(project, userId, last, now, TOP_TASKS).stream()
                .map(r -> new TaskView(r.task().id(), r.task().title(), r.task().createdAt(), r.score())).toList();
        List<MemoryView> decisions = views(project, userId, MemoryKind.DECISION, RECENT_DECISIONS);
        List<MemoryView> fixes = views(project, userId, MemoryKind.FIX, RECENT_FIXES);
        return new Resume(last, where, tasks, decisions, fixes, drift.alerts(projectId).size());
    }

    public String contextPack(UUID projectId, ContextPackBuilder.Format format, int tokenBudget) {
        Project project = projects.require(projectId);
        UUID userId = currentUser.id();
        Instant now = Instant.now();
        LastSession last = lastSession(project, userId);
        List<DecisionItem> decisions = drift.activeDecisions(project, userId);
        List<ContextPackBuilder.Fix> fixes = new ArrayList<>();
        for (MemoryView f : views(project, userId, MemoryKind.FIX, RECENT_FIXES)) {
            fixes.add(new ContextPackBuilder.Fix(f.title(), f.body(), f.createdAt()));
        }
        ContextPackBuilder.Input in = new ContextPackBuilder.Input(project.name(), decisions,
                topTasks(project, userId, last, now, 10), whereYouStopped(last, userId), fixes, now);
        return packs.build(in, format, tokenBudget);
    }

    private List<TaskPrioritizer.Ranked> topTasks(Project project, UUID userId, LastSession last, Instant now, int k) {
        List<TaskPrioritizer.Task> open = new ArrayList<>();
        for (MemoryRepository.StoredMemory m : memories.list(project.id(), userId, MemoryKind.TASK.name(), "ACTIVE", 500)) {
            open.add(new TaskPrioritizer.Task(m.id(), codec.open(m.payload(), userId).title(), m.createdAt(), m.conversationId()));
        }
        return prioritizer.top(open, last == null ? null : last.conversationId(), now, k);
    }

    private List<MemoryView> views(Project project, UUID userId, MemoryKind kind, int limit) {
        return memories.list(project.id(), userId, kind.name(), "ACTIVE", limit).stream()
                .map(m -> MemoryView.of(m, codec, userId)).toList();
    }

    private LastSession lastSession(Project project, UUID userId) {
        return jdbc.sql("""
                SELECT c.id, c.title, MAX(m.sent_at) AS last_at, COUNT(m.id) AS n
                FROM conversations c JOIN messages m ON m.conversation_id = c.id
                WHERE c.project_id = :p AND c.user_id = :u
                GROUP BY c.id, c.title
                ORDER BY last_at DESC
                LIMIT 1""")
                .param("p", project.id())
                .param("u", userId)
                .query((rs, n) -> new LastSession(Db.uuid(rs, "id"), rs.getString("title"),
                        Db.instant(rs, "last_at"), rs.getInt("n")))
                .optional()
                .orElse(null);
    }

    /** Plain lines: which conversation, what was last asked, and how the reply began. */
    private List<String> whereYouStopped(LastSession last, UUID userId) {
        if (last == null) return List.of();
        List<String> lines = new ArrayList<>();
        lines.add("Last session: " + last.title() + " (" + DAY.format(last.lastActivity()) + ")");
        record Msg(String role, String text) {
        }
        List<Msg> recent = jdbc.sql("""
                SELECT role, body FROM messages
                WHERE conversation_id = :c AND user_id = :u
                ORDER BY seq DESC LIMIT 6""")
                .param("c", last.conversationId())
                .param("u", userId)
                .query((rs, n) -> new Msg(rs.getString("role"), cipher.decrypt(rs.getBytes("body"), userId.toString())))
                .list();
        recent.stream().filter(m -> m.role().equals("user")).findFirst()
                .ifPresent(m -> lines.add("You asked: " + quote(m.text())));
        recent.stream().filter(m -> m.role().equals("assistant")).findFirst()
                .ifPresent(m -> lines.add("The reply began: " + quote(firstSentences(m.text()))));
        return lines;
    }

    private static String firstSentences(String text) {
        String[] parts = text.strip().split("(?<=[.!?])\\s+", 3);
        return parts.length >= 2 ? parts[0] + " " + parts[1] : parts[0];
    }

    private static String quote(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= QUOTE_CHARS ? t : t.substring(0, QUOTE_CHARS - 1) + "…";
    }
}
