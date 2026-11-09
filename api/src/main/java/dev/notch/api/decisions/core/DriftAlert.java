package dev.notch.api.decisions.core;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that goes against an active decision.
 *
 * <ul>
 *   <li>{@code DECISION_CONFLICT}: a newer decision contradicts an older one. The fix is
 *       usually to mark the older one as replaced.</li>
 *   <li>{@code USAGE_DRIFT}: a later conversation starts using a tool the decision ruled out.</li>
 * </ul>
 */
public record DriftAlert(String key, Type type, Severity severity, UUID decisionId, String decisionTitle,
                         Instant decidedAt, UUID conflictingDecisionId, Evidence evidence, String message) {

    public enum Type { DECISION_CONFLICT, USAGE_DRIFT }

    public enum Severity { HIGH, MEDIUM }

    /** Where the drift was seen: a span of one chunk. */
    public record Evidence(UUID chunkId, UUID conversationId, String conversationTitle, Instant sentAt,
                           String sentence, int start, int end) {
    }
}
