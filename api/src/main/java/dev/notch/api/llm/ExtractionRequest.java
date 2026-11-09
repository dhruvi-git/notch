package dev.notch.api.llm;

import java.util.List;
import java.util.Objects;

/** One conversation sent for memory extraction. Message indexes are positions in the list. */
public record ExtractionRequest(String conversationTitle, List<SourceMessage> messages) {

    public record SourceMessage(String role, String text) {
        public SourceMessage {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(text, "text");
        }
    }

    public ExtractionRequest {
        Objects.requireNonNull(conversationTitle, "conversationTitle");
        messages = List.copyOf(messages);
    }
}
