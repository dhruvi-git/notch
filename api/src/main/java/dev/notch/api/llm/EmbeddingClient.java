package dev.notch.api.llm;

import java.util.List;

/** Turns text into vectors for semantic search. */
public interface EmbeddingClient {

    int dimensions();

    /** One unit-length vector per stored passage; null for inputs with nothing to embed. */
    List<float[]> embed(List<String> texts);

    /** A vector for search text. Providers that embed queries differently from documents override this. */
    default float[] embedQuery(String text) {
        return embed(List.of(text)).get(0);
    }

    /**
     * Neighbours below this cosine similarity are ignored. Each model spreads similarities
     * differently, so the cut-off belongs to the model.
     */
    default double minSimilarity() {
        return dev.notch.api.search.core.SearchPipeline.SEMANTIC_MIN;
    }

    /** The vector space: stored with each vector, so vectors from different models are never compared. */
    String name();
}
