package dev.notch.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Typed view of the {@code notch.*} settings in application.yml. */
@ConfigurationProperties(prefix = "notch")
public record NotchProperties(Crypto crypto, Limits limits, Cors cors, Embedding embedding,
                              Ingest ingest, Jobs jobs) {

    public record Crypto(String key) {
    }

    public record Limits(int importsPerDay, int questionsPerDay) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Embedding(int dimensions) {
    }

    public record Ingest(long maxFileBytes, int maxChunkChars) {
    }

    public record Jobs(long pollDelayMs) {
    }
}
