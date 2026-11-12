package dev.notch.api.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Posts JSON to a model provider. Errors carry only the status, never request or response content. */
final class ModelHttp {

    static final class ModelException extends Exception {
        ModelException(String message) {
            super(message);
        }

        ModelException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient http;
    private final Duration timeout;

    ModelHttp(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    String post(URI uri, Map<String, String> headers, String json) throws ModelException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(b::header);
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ModelException("request to " + uri.getHost() + " failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("interrupted", e);
        }
        if (r.statusCode() != 200) throw new ModelException(uri.getHost() + " returned " + r.statusCode());
        return r.body();
    }
}
