package dev.notch.api.live;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** HTTP helpers for tests that run against a live API at {@code NOTCH_API_URL}. */
final class LiveApi {

    static final String BASE = System.getenv("NOTCH_API_URL");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private LiveApi() {
    }

    record Res(int status, String body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json() {
            return JSON.readValue(body, Map.class);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list() {
            return JSON.readValue(body, List.class);
        }

        int num(String field) {
            return ((Number) json().get(field)).intValue();
        }
    }

    static Res call(String method, String path, String token, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(60))
                .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    static Res get(String path, String token) throws Exception {
        return call("GET", path, token, null);
    }

    static Res upload(String path, String token, Path file) throws Exception {
        String boundary = "----notch" + UUID.randomUUID();
        byte[] content = Files.readAllBytes(file);
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + file.getFileName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes();
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes();
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    /** A session token for a test user, via the local profile's dev login. */
    static String login(String handle) throws Exception {
        Res r = call("POST", "/auth/dev-login", null, "{\"handle\":\"" + handle + "\"}");
        assertEquals(200, r.status(), "dev-login needs the API running with the local profile: " + r.body());
        return (String) r.json().get("token");
    }

    static Path sample(String name) {
        return Path.of("..", "samples", name);
    }

    /** Polls until {@code done} accepts the response, or fails after {@code seconds}. */
    static Res waitFor(String what, int seconds, ThrowingSupplier<Res> call, Predicate<Res> done) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        Res last = null;
        while (System.currentTimeMillis() < deadline) {
            last = call.get();
            if (done.test(last)) return last;
            Thread.sleep(500);
        }
        fail("Timed out after " + seconds + "s waiting for " + what + ". Last response: "
                + (last == null ? "none" : last.status() + " " + last.body()));
        return last;
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
