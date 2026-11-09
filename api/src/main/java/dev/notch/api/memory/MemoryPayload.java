package dev.notch.api.memory;

import java.util.List;

/** The content of a memory. Stored as encrypted JSON so none of it sits in plain text. */
public record MemoryPayload(String title, String body, String rationale, List<String> rejectedOptions) {
    public MemoryPayload {
        rejectedOptions = rejectedOptions == null ? List.of() : List.copyOf(rejectedOptions);
    }
}
