package dev.notch.api.decisions.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** An active decision as drift detection and context packs see it. */
public record DecisionItem(UUID id, String title, String rationale, List<String> rejectedOptions,
                           Instant decidedAt, UUID sourceMessageId, UUID conversationId) {
    public DecisionItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(decidedAt, "decidedAt");
        rejectedOptions = rejectedOptions == null ? List.of() : List.copyOf(rejectedOptions);
    }
}
