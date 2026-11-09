package dev.notch.api.ingest.core;

import java.util.Locale;

/** Supported import formats. The name, lower-cased, is stored as the conversation source. */
public enum ImportFormat {
    CHATGPT, CLAUDE, MARKDOWN, TEXT;

    public String source() {
        return name().toLowerCase(Locale.ROOT);
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
