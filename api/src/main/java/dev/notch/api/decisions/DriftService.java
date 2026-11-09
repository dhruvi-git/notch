package dev.notch.api.decisions;

import dev.notch.api.config.ApiException;
import dev.notch.api.decisions.core.DecisionItem;
import dev.notch.api.decisions.core.DriftAlert;
import dev.notch.api.decisions.core.DriftDetector;
import dev.notch.api.llm.LlmClient;
import dev.notch.api.llm.MemoryKind;
import dev.notch.api.memory.MemoryCodec;
import dev.notch.api.memory.MemoryPayload;
import dev.notch.api.memory.MemoryRepository;
import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectService;
import dev.notch.api.search.IndexRegistry;
import dev.notch.api.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Drift alerts: computed on demand from the active decisions and the project index, minus the
 * ones the user dismissed. The rules find candidates; for non-private projects the language
 * model gets a final say on each one.
 */
@Service
public class DriftService {

    static final int MAX_DECISIONS = 500;

    private final MemoryRepository memories;
    private final MemoryCodec codec;
    private final ProjectService projects;
    private final CurrentUser currentUser;
    private final IndexRegistry indexes;
    private final LlmClient llm;
    private final JdbcClient jdbc;
    private final DriftDetector detector = new DriftDetector();

    public DriftService(MemoryRepository memories, MemoryCodec codec, ProjectService projects,
                        CurrentUser currentUser, IndexRegistry indexes, LlmClient llm, JdbcClient jdbc) {
        this.memories = memories;
        this.codec = codec;
        this.projects = projects;
        this.currentUser = currentUser;
        this.indexes = indexes;
        this.llm = llm;
        this.jdbc = jdbc;
    }

    public List<DriftAlert> alerts(UUID projectId) {
        Project project = projects.require(projectId);
        UUID userId = currentUser.id();
        List<DecisionItem> active = activeDecisions(project, userId);
        if (active.isEmpty()) return List.of();
        Map<UUID, String> titles = new HashMap<>();
        for (DecisionItem d : active) titles.put(d.id(), d.title());

        Set<String> dismissed = new HashSet<>(jdbc.sql(
                        "SELECT alert_key FROM drift_dismissals WHERE project_id = :p AND user_id = :u")
                .param("p", project.id())
                .param("u", userId)
                .query(String.class)
                .list());
        List<DriftAlert> out = new ArrayList<>();
        for (DriftAlert a : detector.detect(active, indexes.get(project))) {
            if (dismissed.contains(a.key())) continue;
            if (!project.isPrivate()) {
                String evidence = a.evidence() != null ? a.evidence().sentence() : titles.get(a.conflictingDecisionId());
                if (!llm.confirmsDrift(a.decisionTitle(), evidence)) continue;
            }
            out.add(a);
        }
        return out;
    }

    public void dismiss(UUID projectId, String alertKey) {
        Project project = projects.require(projectId);
        if (alertKey == null || !alertKey.matches("[0-9a-f]{24}")) {
            throw ApiException.badRequest("invalid_alert", "That alert key is not valid.");
        }
        jdbc.sql("""
                INSERT INTO drift_dismissals (project_id, user_id, alert_key) VALUES (:p, :u, :k)
                ON CONFLICT (project_id, alert_key) DO NOTHING""")
                .param("p", project.id())
                .param("u", currentUser.id())
                .param("k", alertKey)
                .update();
    }

    List<DecisionItem> activeDecisions(Project project, UUID userId) {
        List<DecisionItem> out = new ArrayList<>();
        for (MemoryRepository.StoredMemory m : memories.list(project.id(), userId, MemoryKind.DECISION.name(),
                "ACTIVE", MAX_DECISIONS)) {
            MemoryPayload p = codec.open(m.payload(), userId);
            out.add(new DecisionItem(m.id(), p.title(), p.rationale(), p.rejectedOptions(), m.createdAt(),
                    m.sourceMessageId(), m.conversationId()));
        }
        return out;
    }
}
