package dev.notch.guard;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** An active decision, as the guard needs it. */
public record Decision(String id, String title, List<String> rejectedOptions, Instant decidedAt) {
    public Decision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        rejectedOptions = rejectedOptions == null ? List.of() : List.copyOf(rejectedOptions);
    }
}
