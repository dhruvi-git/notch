package dev.notch.api.search.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A chunk as held in a project's in-memory index: its decrypted text plus what search needs
 * to rank and display it. {@code decision} is true when an active decision was extracted
 * from this span.
 */
public record IndexedChunk(UUID chunkId, UUID messageId, UUID conversationId, String conversationTitle,
                           String role, Instant sentAt, String text, int tokens, boolean decision) {
    public IndexedChunk {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(sentAt, "sentAt");
        Objects.requireNonNull(text, "text");
    }
}
