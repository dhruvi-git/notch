package dev.notch.api.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.notch.api.config.NotchProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * GitHub OAuth with the JDK's HTTP client. Only the {@code read:user} scope is requested:
 * Notch needs the user's id, login and avatar, nothing from their repositories.
 */
@Component
public class HttpGitHubClient implements GitHubClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken, String error,
                         @JsonProperty("error_description") String errorDescription) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserResponse(Long id, String login, @JsonProperty("avatar_url") String avatarUrl) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final NotchProperties props;

    public HttpGitHubClient(ObjectMapper mapper, NotchProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public GitHubUser signIn(String code) {
        NotchProperties.Github gh = props.auth().github();
        String form = "client_id=" + enc(gh.clientId()) + "&client_secret=" + enc(gh.clientSecret())
                + "&code=" + enc(code) + "&redirect_uri=" + enc(gh.callbackUrl());
        HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        TokenResponse token = mapper.readValue(send(tokenRequest), TokenResponse.class);
        if (token.accessToken() == null) {
            throw new IllegalStateException("GitHub refused the sign-in code: " + token.error());
        }
        HttpRequest userRequest = HttpRequest.newBuilder(URI.create("https://api.github.com/user"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token.accessToken())
                .header("User-Agent", "notch-api")
                .GET()
                .build();
        UserResponse user = mapper.readValue(send(userRequest), UserResponse.class);
        if (user.id() == null || user.login() == null) throw new IllegalStateException("GitHub returned no user");
        return new GitHubUser(user.id(), user.login(), user.avatarUrl());
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) throw new IllegalStateException("GitHub returned HTTP " + r.statusCode());
            return r.body();
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach GitHub", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling GitHub", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
