package dev.notch.api.project;

import java.time.Instant;
import java.util.UUID;

public record Project(UUID id, UUID userId, String name, boolean isPrivate, Instant createdAt) {
}
