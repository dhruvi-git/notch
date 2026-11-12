package dev.notch.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Typed view of the {@code notch.*} settings in application.yml. */
@ConfigurationProperties(prefix = "notch")
public record NotchProperties(Crypto crypto, Limits limits, Cors cors, Embedding embedding,
                              Ingest ingest, Jobs jobs, Auth auth, Llm llm) {

    public record Crypto(String key) {
    }

    /** capturesPerDay: uploads from notch sync and the Claude Code hook, counted apart from manual imports. */
    public record Limits(int importsPerDay, int questionsPerDay, int capturesPerDay) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /** provider: auto (Voyage when an API key is set), voyage, or offline. */
    public record Embedding(int dimensions, String provider, String model, String apiKey) {
    }

    /** provider: auto (Claude when an API key is set), anthropic, or offline. */
    public record Llm(String provider, String model, String apiKey) {
    }

    public record Ingest(long maxFileBytes, int maxChunkChars) {
    }

    public record Jobs(long pollDelayMs) {
    }

    public record Auth(String stateSecret, int sessionDays, boolean devFallback, Github github) {
    }

    public record Github(String clientId, String clientSecret, String callbackUrl) {
        public boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
