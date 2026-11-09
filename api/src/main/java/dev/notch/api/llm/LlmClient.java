package dev.notch.api.llm;

import java.util.List;

/**
 * The only place Notch talks to a language model. Implementations: an offline heuristic
 * client for tests, development and private projects, and real providers later.
 */
public interface LlmClient {

    /** Finds decisions, tasks, fixes and context in one conversation. */
    List<ExtractedMemory> extractMemories(ExtractionRequest request);

    /** A short name for logs and the UI, such as "offline" or a provider name. */
    String name();
}
