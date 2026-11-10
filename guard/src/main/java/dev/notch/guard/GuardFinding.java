package dev.notch.guard;

import java.time.Instant;

/** A change that goes against a decision. */
public record GuardFinding(Severity severity, Kind kind, String path, int line, String lineText,
                           String tech, String alias, String decisionId, String decisionTitle,
                           Instant decidedAt, String message) {

    public enum Severity { HIGH, MEDIUM }

    public enum Kind {
        /** Uses a tool the decision ruled out. */
        RULED_OUT,
        /** Adds a different tool of the same kind the decision chose (another database, say). */
        DIFFERENT_CHOICE
    }

    /** "package.json:14", or just the path when the line is unknown. */
    public String location() {
        return line > 0 ? path + ":" + line : path;
    }
}
