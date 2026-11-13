package dev.notch.api.llm;

import dev.notch.guard.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Posts JSON to a model provider. Rate limits and overload (429, 502, 503, 504, 529) are retried
 * within a time budget the caller chooses, honouring {@code Retry-After}. Errors carry the status
 * and the provider's own error message (for example "credit balance is too low"), never the
 * request's content.
 */
final class ModelHttp {

    static final class ModelException extends Exception {
        private final int status;

        ModelException(String message) {
            this(message, 0);
        }

        ModelException(String message, int status) {
            super(message);
            this.status = status;
        }

        ModelException(String message, Throwable cause) {
            super(message, cause);
            this.status = 0;
        }

        /** The HTTP status, or 0 when the request never got a response. */
        int status() {
            return status;
        }
    }

    /** Statuses that mean "slow down or try again shortly", not "this request is wrong". */
    static final Set<Integer> RETRYABLE = Set.of(429, 502, 503, 504, 529);
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    /** Every retry waits at least this long, so "Retry-After: 0" still uses up the budget. */
    static final Duration MIN_WAIT = Duration.ofMillis(250);
    /** A hard stop, whatever the budget and the provider's Retry-After say. */
    static final int MAX_ATTEMPTS = 6;
    private static final int MAX_DETAIL = 200;

    private final HttpClient http;
    private final Duration timeout;

    ModelHttp(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Retries rate limits and overload while the total wait stays within {@code retryBudget}, for
     * at most {@link #MAX_ATTEMPTS} attempts. A zero budget means one attempt.
     */
    String post(URI uri, Map<String, String> headers, String json, Duration retryBudget) throws ModelException {
        long budgetMillis = Math.max(0, retryBudget.toMillis());
        long waitedMillis = 0;
        long backoffMillis = FIRST_BACKOFF.toMillis();
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> r = send(uri, headers, json);
            if (r.statusCode() == 200) return r.body();
            ModelException error = new ModelException(describe(uri, r), r.statusCode());
            if (!RETRYABLE.contains(r.statusCode()) || attempt >= MAX_ATTEMPTS || budgetMillis == 0) throw error;
            long waitMillis = Math.max(MIN_WAIT.toMillis(), retryAfterMillis(r).orElse(backoffMillis));
            if (waitedMillis + waitMillis > budgetMillis) throw error;
            sleep(waitMillis);
            waitedMillis += waitMillis;
            backoffMillis = Math.min(MAX_BACKOFF.toMillis(), backoffMillis * 2);
        }
    }

    private HttpResponse<String> send(URI uri, Map<String, String> headers, String json) throws ModelException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(b::header);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ModelException("request to " + uri.getHost() + " failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("interrupted", e);
        }
    }

    /** "api.anthropic.com returned 400: Your credit balance is too low…" */
    static String describe(URI uri, HttpResponse<String> r) {
        String detail = providerMessage(r.body());
        return uri.getHost() + " returned " + r.statusCode() + (detail == null ? "" : ": " + detail);
    }

    /**
     * The provider's error message: Anthropic's {@code error.message}, or {@code detail} /
     * {@code message} at the top level (Voyage and others). Null when the body isn't such JSON.
     */
    static String providerMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            Map<String, Object> root = Json.obj(Json.parse(body));
            Object message = null;
            if (root.get("error") instanceof Map<?, ?> err) message = Json.obj(err).get("message");
            if (message == null) message = root.get("detail");
            if (message == null) message = root.get("message");
            if (!(message instanceof String s) || s.isBlank()) return null;
            String flat = s.replaceAll("\\s+", " ").strip();
            return flat.length() <= MAX_DETAIL ? flat : flat.substring(0, MAX_DETAIL - 1) + "…";
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code Retry-After} in seconds, when the provider sent one we can read. */
    private static java.util.OptionalLong retryAfterMillis(HttpResponse<String> r) {
        return r.headers().firstValue("retry-after").map(v -> {
            try {
                double seconds = Double.parseDouble(v.strip());
                return seconds < 0 ? java.util.OptionalLong.empty() : java.util.OptionalLong.of((long) (seconds * 1000));
            } catch (NumberFormatException e) {
                return java.util.OptionalLong.empty();  // an HTTP date: fall back to our own backoff
            }
        }).orElse(java.util.OptionalLong.empty());
    }

    private static void sleep(long millis) throws ModelException {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("interrupted", e);
        }
    }
}
