package dev.notch.api.llm;

import java.util.List;

/**
 * The only place Notch talks to a language model. Implementations: an offline heuristic
 * client for tests, development and private projects, and real providers later.
 */
public interface LlmClient {

    /** Finds decisions, tasks, fixes and context in one conversation. */
    List<ExtractedMemory> extractMemories(ExtractionRequest request);

    /**
     * Answers a question using only the numbered contexts, citing them as [n]. Implementations
     * must not cite anything outside the request; the caller drops citations that do.
     */
    AnswerDraft answer(AnswerRequest request);

    /**
     * Final check on a drift candidate found by the rules: does {@code evidence} really go
     * against {@code decision}? The offline client trusts the rules; a real model can reject
     * false alarms. Only called for non-private projects.
     */
    default boolean confirmsDrift(String decision, String evidence) {
        return true;
    }

    /** A short name for logs and the UI, such as "offline" or a provider name. */
    String name();
}
