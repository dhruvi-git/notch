package dev.notch.api.ingest.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One conversation read from an import file. {@code startedAt} may be null when unknown. */
public record ParsedConversation(String externalId, String title, Instant startedAt,
                                 List<ParsedMessage> messages) {
    public ParsedConversation {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(title, "title");
        messages = List.copyOf(messages);
    }
}
