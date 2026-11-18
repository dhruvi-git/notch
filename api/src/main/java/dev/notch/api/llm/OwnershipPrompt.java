package dev.notch.api.llm;


/**
 * Asking a model to write one Ownership Check question about a region of a candidate's code.
 *
 * @param focus    the engineering idea the question should be about (a concept label), or null
 * @param fallback the template question; the model should do better, but may keep its theme
 */
public record OwnershipPrompt(String path, int startLine, int endLine, String language, String code, String focus,
                              String fallback) {
}
