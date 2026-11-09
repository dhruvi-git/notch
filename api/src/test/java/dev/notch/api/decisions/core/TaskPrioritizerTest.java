package dev.notch.api.decisions.core;

import dev.notch.engine.select.RandomizedSelect;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskPrioritizerTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private final TaskPrioritizer p = new TaskPrioritizer(new RandomizedSelect(1));

    private static TaskPrioritizer.Task task(String title, int daysAgo, UUID conv) {
        return new TaskPrioritizer.Task(UUID.randomUUID(), title, NOW.minus(Duration.ofDays(daysAgo)), conv);
    }

    @Test
    void recentTasksRankFirst() {
        List<TaskPrioritizer.Ranked> top = p.top(List.of(task("old", 30, null), task("new", 0, null)), null, NOW, 2);
        assertEquals("new", top.get(0).task().title());
    }

    @Test
    void lastSessionTasksGetABoost() {
        UUID last = UUID.randomUUID();
        List<TaskPrioritizer.Ranked> top = p.top(List.of(task("fresh elsewhere", 0, null), task("from last chat", 3, last)), last, NOW, 1);
        assertEquals("from last chat", top.get(0).task().title());
    }

    @Test
    void recencyHalvesEveryWeek() {
        assertEquals(0.6, TaskPrioritizer.score(task("t", 0, null), null, NOW), 1e-9);
        assertEquals(0.3, TaskPrioritizer.score(task("t", 7, null), null, NOW), 1e-9);
    }

    @Test
    void emptyListAndInvalidK() {  // boundary
        assertTrue(p.top(List.of(), null, NOW, 5).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> p.top(List.of(), null, NOW, 0));
    }

    @Test
    void returnsAtMostK() {
        List<TaskPrioritizer.Task> many = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) many.add(task("t" + i, i, null));
        List<TaskPrioritizer.Ranked> top = p.top(many, null, NOW, 5);
        assertEquals(5, top.size());
        assertEquals("t0", top.get(0).task().title());
    }
}
