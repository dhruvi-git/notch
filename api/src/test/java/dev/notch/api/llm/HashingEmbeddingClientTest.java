package dev.notch.api.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HashingEmbeddingClientTest {

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;  // vectors are unit length
    }

    @Test
    void vectorsHaveUnitLengthAndRightSize() {
        float[] v = new HashingEmbeddingClient(64).embed(List.of("postgres index migration")).get(0);
        assertEquals(64, v.length);
        assertEquals(1.0, cosine(v, v), 1e-5);
    }

    @Test
    void sharedWordsMeanHigherSimilarity() {
        HashingEmbeddingClient e = new HashingEmbeddingClient(1024);
        List<float[]> v = e.embed(List.of("postgres connection refused", "postgres connection timeout", "css grid layout"));
        assertTrue(cosine(v.get(0), v.get(1)) > cosine(v.get(0), v.get(2)));
    }

    @Test
    void isDeterministic() {
        HashingEmbeddingClient e = new HashingEmbeddingClient(128);
        assertArrayEquals(e.embed(List.of("same text")).get(0), e.embed(List.of("same text")).get(0));
    }

    @Test
    void textWithoutWordsGivesNull() {  // boundary
        assertNull(new HashingEmbeddingClient(32).embed(List.of("!!! ...")).get(0));
    }

    @Test
    void rejectsTooFewDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new HashingEmbeddingClient(4));
    }
}
