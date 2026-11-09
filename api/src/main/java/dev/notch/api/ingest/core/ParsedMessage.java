package dev.notch.api.ingest.core;

import java.time.Instant;
import java.util.Objects;

/** One message read from an import file, before redaction and storage. */
public record ParsedMessage(String externalId, String role, String text, Instant sentAt) {
    public ParsedMessage {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }
}
