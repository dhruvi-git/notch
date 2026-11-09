package dev.notch.api.decisions;

import dev.notch.api.config.ApiException;
import dev.notch.api.llm.MemoryKind;
import dev.notch.api.memory.MemoryCodec;
import dev.notch.api.memory.MemoryPayload;
import dev.notch.api.memory.MemoryRepository;
import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectService;
import dev.notch.api.search.IndexRegistry;
import dev.notch.api.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The decision ledger's write side: add memories by hand, mark tasks done, and replace a
 * decision with a newer one while keeping the history.
 */
@Service
public class DecisionService {

    static final int MAX_HISTORY = 50;

    private final MemoryRepository memories;
    private final MemoryCodec codec;
    private final ProjectService projects;
    private final CurrentUser currentUser;
    private final IndexRegistry indexes;

    public DecisionService(MemoryRepository memories, MemoryCodec codec, ProjectService projects,
                           CurrentUser currentUser, IndexRegistry indexes) {
        this.memories = memories;
        this.codec = codec;
        this.projects = projects;
        this.currentUser = currentUser;
        this.indexes = indexes;
    }

    public MemoryView create(UUID projectId, String kind, String title, String body, String rationale,
                             List<String> rejected) {
        Project project = projects.require(projectId);
        String k = parseKind(kind);
        if (k.equals(MemoryKind.FIX.name())) {
            throw ApiException.badRequest("invalid_kind", "Fixes come from imported conversations; add a decision, task or context.");
        }
        UUID userId = currentUser.id();
        UUID id = UUID.randomUUID();
        MemoryPayload payload = new MemoryPayload(title.strip(), blankToNull(body), blankToNull(rationale), clean(rejected));
        memories.insertManual(id, project.id(), userId, k, codec.seal(payload, userId), Instant.now());
        return view(id);
    }

    /** Marks a task done or reopens it. Decisions change state only through {@link #supersede}. */
    public MemoryView setStatus(UUID memoryId, String status) {
        MemoryRepository.StoredMemory m = require(memoryId);
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!s.equals("DONE") && !s.equals("ACTIVE")) {
            throw ApiException.badRequest("invalid_status", "status must be done or active.");
        }
        if (m.kind().equals(MemoryKind.DECISION.name())) {
            throw ApiException.badRequest("use_supersede", "To retire a decision, mark it as replaced by a newer one.");
        }
        memories.updateStatus(m.id(), currentUser.id(), s);
        return view(m.id());
    }

    /**
     * Marks {@code memoryId} as replaced. The replacement is either an existing active decision
     * in the same project ({@code replacementId}), or a new decision created from the given text.
     */
    @Transactional
    public MemoryView supersede(UUID memoryId, UUID replacementId, String title, String rationale, List<String> rejected) {
        MemoryRepository.StoredMemory original = require(memoryId);
        if (!original.kind().equals(MemoryKind.DECISION.name())) {
            throw ApiException.badRequest("not_a_decision", "Only decisions can be replaced.");
        }
        if (!original.status().equals("ACTIVE")) {
            throw ApiException.conflict("already_replaced", "This decision has already been replaced.");
        }
        UUID userId = currentUser.id();
        UUID replacement;
        if (replacementId != null) {
            MemoryRepository.StoredMemory r = memories.find(replacementId, userId)
                    .orElseThrow(() -> ApiException.notFound("Replacement decision"));
            if (r.id().equals(original.id())) throw ApiException.badRequest("same_decision", "A decision cannot replace itself.");
            if (!r.projectId().equals(original.projectId())) {
                throw ApiException.badRequest("other_project", "The replacement must be in the same project.");
            }
            if (!r.kind().equals(MemoryKind.DECISION.name()) || !r.status().equals("ACTIVE")) {
                throw ApiException.badRequest("invalid_replacement", "The replacement must be an active decision.");
            }
            if (leadsTo(r, original.id(), userId)) {
                throw ApiException.badRequest("cycle", "That would make the history loop back on itself.");
            }
            replacement = r.id();
        } else {
            if (title == null || title.isBlank()) {
                throw ApiException.badRequest("missing_replacement", "Give a replacementId, or the title of the new decision.");
            }
            replacement = UUID.randomUUID();
            MemoryPayload payload = new MemoryPayload(title.strip(), null, blankToNull(rationale), clean(rejected));
            memories.insertManual(replacement, original.projectId(), userId, MemoryKind.DECISION.name(),
                    codec.seal(payload, userId), Instant.now());
        }
        if (!memories.supersede(original.id(), replacement, userId)) {
            throw ApiException.conflict("already_replaced", "This decision was replaced by someone else just now.");
        }
        indexes.rebuildAsync(original.projectId());  // decision flags on chunks changed
        return view(replacement);
    }

    /** The decision's full history, oldest first: what it replaced and what replaced it. */
    public List<MemoryView> history(UUID memoryId) {
        MemoryRepository.StoredMemory start = require(memoryId);
        UUID userId = currentUser.id();
        List<MemoryRepository.StoredMemory> before = new ArrayList<>();
        Set<UUID> seen = new HashSet<>(Set.of(start.id()));
        MemoryRepository.StoredMemory cur = start;
        while (before.size() < MAX_HISTORY) {
            var prev = memories.predecessor(cur.id(), userId);
            if (prev.isEmpty() || !seen.add(prev.get().id())) break;
            before.add(prev.get());
            cur = prev.get();
        }
        Collections.reverse(before);
        List<MemoryRepository.StoredMemory> chain = new ArrayList<>(before);
        chain.add(start);
        cur = start;
        while (cur.supersededBy() != null && chain.size() < MAX_HISTORY) {
            var next = memories.find(cur.supersededBy(), userId);
            if (next.isEmpty() || !seen.add(next.get().id())) break;
            chain.add(next.get());
            cur = next.get();
        }
        return chain.stream().map(m -> MemoryView.of(m, codec, userId)).toList();
    }

    private boolean leadsTo(MemoryRepository.StoredMemory from, UUID target, UUID userId) {
        Set<UUID> seen = new HashSet<>();
        MemoryRepository.StoredMemory cur = from;
        while (cur.supersededBy() != null && seen.add(cur.id())) {
            if (cur.supersededBy().equals(target)) return true;
            cur = memories.find(cur.supersededBy(), userId).orElse(null);
            if (cur == null) return false;
        }
        return false;
    }

    private MemoryRepository.StoredMemory require(UUID memoryId) {
        return memories.find(memoryId, currentUser.id()).orElseThrow(() -> ApiException.notFound("Memory"));
    }

    private MemoryView view(UUID id) {
        return MemoryView.of(require(id), codec, currentUser.id());
    }

    private static String parseKind(String kind) {
        try {
            return MemoryKind.valueOf(kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_kind", "kind must be decision, task or context.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static List<String> clean(List<String> items) {
        if (items == null) return List.of();
        return items.stream().filter(x -> x != null && !x.isBlank()).map(String::strip).limit(10).toList();
    }
}
