package dev.notch.api.llm;

import java.util.List;

/** Turns text into vectors for semantic search. */
public interface EmbeddingClient {

    int dimensions();

    /** One unit-length vector per input; null for inputs with nothing to embed. */
    List<float[]> embed(List<String> texts);

    String name();
}
