package dev.notch.api.llm;

import dev.notch.api.search.core.QueryTerms;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A question plus the ranked source chunks an answer may cite, numbered from 1. */
public record AnswerRequest(String question, QueryTerms terms, List<Context> contexts) {

    public record Context(int n, UUID chunkId, String conversationTitle, Instant sentAt, String text,
                          boolean decision) {
        public Context {
            Objects.requireNonNull(chunkId, "chunkId");
            Objects.requireNonNull(text, "text");
        }
    }

    public AnswerRequest {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(terms, "terms");
        contexts = List.copyOf(contexts);
    }
}
