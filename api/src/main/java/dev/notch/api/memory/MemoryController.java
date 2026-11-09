package dev.notch.api.memory;

import dev.notch.api.config.ApiException;
import dev.notch.api.llm.MemoryKind;
import dev.notch.api.project.ProjectService;
import dev.notch.api.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
public class MemoryController {

    public record MemoryResponse(UUID id, String kind, String status, String title, String body,
                                 String rationale, List<String> rejectedOptions, UUID supersededBy,
                                 UUID sourceMessageId, Integer sourceStart, Integer sourceEnd,
                                 UUID conversationId, String conversationTitle, Instant createdAt) {
    }

    private static final Set<String> STATUSES = Set.of("ACTIVE", "SUPERSEDED", "DONE");

    private final MemoryRepository memories;
    private final MemoryCodec codec;
    private final ProjectService projects;
    private final CurrentUser currentUser;

    public MemoryController(MemoryRepository memories, MemoryCodec codec, ProjectService projects,
                            CurrentUser currentUser) {
        this.memories = memories;
        this.codec = codec;
        this.projects = projects;
        this.currentUser = currentUser;
    }

    /** Lists memories, e.g. {@code ?kind=decision&status=active} for the decision ledger. */
    @GetMapping("/api/projects/{projectId}/memories")
    public List<MemoryResponse> list(@PathVariable UUID projectId,
                                     @RequestParam(required = false) String kind,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "100") int limit) {
        projects.require(projectId);
        String k = normalizeKind(kind);
        String s = normalizeStatus(status);
        if (limit < 1 || limit > 500) throw ApiException.badRequest("invalid_limit", "limit must be between 1 and 500.");
        UUID userId = currentUser.id();
        return memories.list(projectId, userId, k, s, limit).stream().map(m -> {
            MemoryPayload p = codec.open(m.payload(), userId);
            return new MemoryResponse(m.id(), m.kind(), m.status(), p.title(), p.body(), p.rationale(),
                    p.rejectedOptions(), m.supersededBy(), m.sourceMessageId(), m.sourceStart(),
                    m.sourceEnd(), m.conversationId(), m.conversationTitle(), m.createdAt());
        }).toList();
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        try {
            return MemoryKind.valueOf(kind.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_kind", "kind must be decision, task, fix or context.");
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) throw ApiException.badRequest("invalid_status", "status must be active, superseded or done.");
        return s;
    }
}
