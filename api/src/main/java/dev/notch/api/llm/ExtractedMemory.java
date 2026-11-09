package dev.notch.api.llm;

import java.util.List;
import java.util.Objects;

/**
 * A memory found in a conversation. {@code messageIndex}, {@code start} and {@code end} point
 * at the exact source span, so every memory can be cited.
 */
public record ExtractedMemory(MemoryKind kind, String title, String body, String rationale,
                              List<String> rejectedOptions, int messageIndex, int start, int end) {
    public ExtractedMemory {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        rejectedOptions = rejectedOptions == null ? List.of() : List.copyOf(rejectedOptions);
        if (messageIndex < 0 || start < 0 || end < start) {
            throw new IllegalArgumentException("invalid source span");
        }
    }
}
