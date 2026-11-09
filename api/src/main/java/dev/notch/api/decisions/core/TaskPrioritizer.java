package dev.notch.api.decisions.core;

import dev.notch.engine.select.RandomizedSelect;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Picks the open tasks most worth showing on "Where you stopped": recent ones, and ones from
 * the last conversation, rank first. Selection uses randomized Quickselect, so ranking stays
 * linear in the number of open tasks.
 */
public final class TaskPrioritizer {

    public record Task(UUID id, String title, Instant createdAt, UUID conversationId) {
        public Task {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record Ranked(Task task, double score) {
    }

    static final Duration HALF_LIFE = Duration.ofDays(7);
    static final double RECENCY_WEIGHT = 0.6;
    static final double LAST_SESSION_WEIGHT = 0.4;

    private final RandomizedSelect select;

    public TaskPrioritizer(RandomizedSelect select) {
        this.select = Objects.requireNonNull(select, "select");
    }

    public List<Ranked> top(List<Task> open, UUID lastConversationId, Instant now, int k) {
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        List<Ranked> scored = open.stream().map(t -> new Ranked(t, score(t, lastConversationId, now))).toList();
        return select.topK(scored, k, Comparator.comparingDouble(Ranked::score)
                .thenComparing(r -> r.task().createdAt()));
    }

    static double score(Task t, UUID lastConversationId, Instant now) {
        double ageDays = Math.max(0, Duration.between(t.createdAt(), now).toMinutes()) / 1440.0;
        double recency = Math.pow(0.5, ageDays / HALF_LIFE.toDays());
        double lastSession = lastConversationId != null && lastConversationId.equals(t.conversationId()) ? 1 : 0;
        return RECENCY_WEIGHT * recency + LAST_SESSION_WEIGHT * lastSession;
    }
}
