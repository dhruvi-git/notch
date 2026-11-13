package dev.notch.api.llm;

import java.util.List;
import java.util.Objects;

/**
 * One conversation sent for memory extraction. Message indexes are positions in the list.
 * {@code existing} lists what the project already holds (decisions in force, open tasks), under
 * short references like "D1" and "T1", so a model can say a new decision replaces one, or that a
 * task is done.
 */
public record ExtractionRequest(String conversationTitle, List<SourceMessage> messages, List<Existing> existing) {

    public record Existing(String ref, MemoryKind kind, String title) {
    }

    public ExtractionRequest(String conversationTitle, List<SourceMessage> messages) {
        this(conversationTitle, messages, List.of());
    }

    public record SourceMessage(String role, String text) {
        public SourceMessage {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(text, "text");
        }
    }

    public ExtractionRequest {
        Objects.requireNonNull(conversationTitle, "conversationTitle");
        messages = List.copyOf(messages);
        existing = existing == null ? List.of() : List.copyOf(existing);
    }
}
