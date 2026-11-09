package dev.notch.api.llm;

import java.util.List;
import java.util.UUID;

/**
 * An answer with numbered citations. Each citation points at the exact span of a source chunk
 * that supports it. {@code grounded} is false when nothing in the project supported an answer.
 */
public record AnswerDraft(String text, List<Citation> citations, boolean grounded) {

    public record Citation(int n, UUID chunkId, int start, int end) {
    }

    public AnswerDraft {
        citations = List.copyOf(citations);
    }
}
