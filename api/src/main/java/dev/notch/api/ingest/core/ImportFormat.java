package dev.notch.api.ingest.core;

import java.util.Locale;

/** Supported import formats. The name, lower-cased, is stored as the conversation source. */
public enum ImportFormat {
    CHATGPT, CLAUDE, MARKDOWN, TEXT,
    /** Captured automatically by the CLI, never uploaded as a file. */
    CLAUDE_CODE, GIT;

    public String source() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Formats that only arrive through automatic capture (notch sync), not file upload. */
    public boolean captured() {
        return this == CLAUDE_CODE || this == GIT;
    }

    /** The capture format for a source name ("claude-code", "git"), or null. */
    public static ImportFormat capturedFrom(String source) {
        for (ImportFormat f : values()) if (f.captured() && f.source().equals(source)) return f;
        return null;
    }

    /** Parses a user-supplied format name; null or blank means auto-detect. */
    public static ImportFormat parseOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown format '" + value + "'; use chatgpt, claude, markdown or text");
        }
    }
}
