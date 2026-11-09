package dev.notch.api.ingest.core;

/** A span {@code [start, end)} of a message's text. */
public record Chunk(int start, int end, String text) {

    /** Rough token estimate: about four characters per token for English and code. */
    public int estimatedTokens() {
        return (text.length() + 3) / 4;
    }
}
