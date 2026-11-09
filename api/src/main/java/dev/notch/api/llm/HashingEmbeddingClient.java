package dev.notch.api.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An offline embedding: the feature-hashing trick over word and identifier tokens, scaled to
 * unit length. Texts sharing words get high cosine similarity. It has no real understanding
 * of meaning, which is fine for tests, development and the demo's offline mode.
 */
public final class HashingEmbeddingClient implements EmbeddingClient {

    private final int dimensions;

    public HashingEmbeddingClient(int dimensions) {
        if (dimensions < 8) throw new IllegalArgumentException("dimensions must be at least 8");
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(embedOne(t));
        return out;
    }

    private float[] embedOne(String text) {
        float[] v = new float[dimensions];
        boolean any = false;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (token.length() < 2) continue;
            long h = fnv1a(token);
            int idx = (int) Math.floorMod(h, (long) dimensions);
            v[idx] += (h >>> 63) == 0 ? 1f : -1f;  // signed hashing reduces collision bias
            any = true;
        }
        if (!any) return null;
        double norm = 0;
        for (float x : v) norm += x * x;
        if (norm == 0) return null;
        float inv = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
        return v;
    }

    private static long fnv1a(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }

    @Override
    public String name() {
        return "offline-hashing";
    }
}
