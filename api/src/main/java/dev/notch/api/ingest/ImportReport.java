package dev.notch.api.ingest;

import java.time.Instant;
import java.util.UUID;

/**
 * What an import did. {@code bloomFalsePositives} counts chunks the Bloom filter flagged as
 * possibly seen that the exact check found to be new.
 */
public record ImportReport(UUID id, UUID projectId, String filename, String format, String status,
                           int conversationsAdded, int conversationsUpdated, int messagesAdded,
                           int messagesSkipped, int chunksAdded, int duplicateChunks,
                           int bloomFalsePositives, int secretsRedacted, int memoriesExtracted,
                           String error, Instant createdAt, Instant finishedAt) {
}
