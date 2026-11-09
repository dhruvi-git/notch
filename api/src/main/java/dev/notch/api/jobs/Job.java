package dev.notch.api.jobs;

import java.util.UUID;

public record Job(long id, String kind, UUID projectId, UUID importId, int attempts) {
}
