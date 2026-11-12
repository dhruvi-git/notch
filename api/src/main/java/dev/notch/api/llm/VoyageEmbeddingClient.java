package dev.notch.api.llm;

import dev.notch.guard.Json;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Voyage AI embeddings for semantic search. Stored passages are embedded as documents and search
 * text as queries, which Voyage recommends for retrieval. Failures are thrown: an import job
 * retries later, and search carries on with exact matching alone.
 */
public final class VoyageEmbeddingClient implements EmbeddingClient {

    public record Config(String apiKey, String model, int dimensions, URI baseUrl, Duration timeout) {
        public Config {
            if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("an API key is required");
            if (model == null || model.isBlank()) model = "voyage-4-lite";
            if (baseUrl == null) baseUrl = URI.create("https://api.voyageai.com");
            if (timeout == null) timeout = Duration.ofSeconds(30);
        }
    }

    static final int BATCH = 64;

    private final Config config;
    private final ModelHttp http;

    public VoyageEmbeddingClient(Config config) {
        this.config = config;
        this.http = new ModelHttp(config.timeout());
    }

    @Override
    public int dimensions() {
        return config.dimensions();
    }

    /** Identifies the vector space: vectors from different models or sizes are never compared. */
    @Override
    public String name() {
        return config.model() + "-" + config.dimensions();
    }

    /** Real embeddings separate related and unrelated text less sharply than shared words do. */
    @Override
    public double minSimilarity() {
        return 0.2;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embed(texts, "document");
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(List.of(text), "query").get(0);
    }

    private List<float[]> embed(List<String> texts, String inputType) {
        float[][] out = new float[texts.size()][];
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) if (texts.get(i) != null && !texts.get(i).isBlank()) todo.add(i);
        for (int from = 0; from < todo.size(); from += BATCH) {
            List<Integer> batch = todo.subList(from, Math.min(todo.size(), from + BATCH));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("input", batch.stream().map(texts::get).toList());
            body.put("model", config.model());
            body.put("input_type", inputType);
            body.put("output_dimension", config.dimensions());
            String raw;
            try {
                raw = http.post(config.baseUrl().resolve("/v1/embeddings"),
                        Map.of("Authorization", "Bearer " + config.apiKey()), Json.write(body));
            } catch (ModelHttp.ModelException e) {
                throw new IllegalStateException("Embeddings unavailable: " + e.getMessage(), e);
            }
            List<Object> data = Json.arr(Json.obj(Json.parse(raw)).get("data"));
            for (Object o : data) {
                Map<String, Object> d = Json.obj(o);
                int index = ((Number) d.get("index")).intValue();
                List<Object> values = Json.arr(d.get("embedding"));
                if (values.size() != config.dimensions()) {
                    throw new IllegalStateException("Expected " + config.dimensions() + " dimensions, got " + values.size());
                }
                float[] v = new float[values.size()];
                for (int k = 0; k < v.length; k++) v[k] = ((Number) values.get(k)).floatValue();
                out[batch.get(index)] = unit(v);
            }
        }
        return Arrays.asList(out);
    }

    private static float[] unit(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return v;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        return v;
    }
}
