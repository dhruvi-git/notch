package dev.notch.guard;

import java.util.List;
import java.util.Objects;

/**
 * The lines a change adds to one file. Removed lines are not kept: deleting a dependency never
 * goes against a decision to avoid it.
 */
public record FileChange(String path, List<Line> added) {

    /** An added line; {@code number} is its line number in the new file, or 0 when unknown. */
    public record Line(int number, String text) {
    }

    public FileChange {
        Objects.requireNonNull(path, "path");
        added = List.copyOf(added);
    }
}
