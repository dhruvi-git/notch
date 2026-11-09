package dev.notch.api.decisions;

import dev.notch.api.memory.MemoryCodec;
import dev.notch.api.memory.MemoryPayload;
import dev.notch.api.memory.MemoryRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A memory with its payload decrypted, as returned by the decision endpoints. */
public record MemoryView(UUID id, UUID projectId, String kind, String status, String title, String body,
                         String rationale, List<String> rejectedOptions, UUID supersededBy,
                         UUID sourceMessageId, UUID conversationId, Instant createdAt) {

    static MemoryView of(MemoryRepository.StoredMemory m, MemoryCodec codec, UUID userId) {
        MemoryPayload p = codec.open(m.payload(), userId);
        return new MemoryView(m.id(), m.projectId(), m.kind(), m.status(), p.title(), p.body(), p.rationale(),
                p.rejectedOptions(), m.supersededBy(), m.sourceMessageId(), m.conversationId(), m.createdAt());
    }
}
