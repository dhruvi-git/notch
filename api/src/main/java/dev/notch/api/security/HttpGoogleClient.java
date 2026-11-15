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
 * Google sign-in (OAuth 2.0 / OpenID Connect) with the JDK's HTTP client. Only the
 * {@code openid email profile} scopes are requested: Notch needs the account's id, name, email and
 * picture, nothing else. The account is read from Google's userinfo endpoint over TLS with the
 * access token, so no ID token signature has to be checked here.
 */
@Component
public class HttpGoogleClient implements GoogleClient {

    static final String AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN = "https://oauth2.googleapis.com/token";
    static final String USERINFO = "https://openidconnect.googleapis.com/v1/userinfo";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken, String error,
                         @JsonProperty("error_description") String errorDescription) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserInfo(String sub, String email, @JsonProperty("email_verified") Boolean emailVerified, String name,
                    String picture) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final NotchProperties props;

    public HttpGoogleClient(ObjectMapper mapper, NotchProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public GoogleUser signIn(String code) {
        NotchProperties.Google g = props.auth().google();
        String form = "client_id=" + enc(g.clientId()) + "&client_secret=" + enc(g.clientSecret())
                + "&code=" + enc(code) + "&redirect_uri=" + enc(g.callbackUrl()) + "&grant_type=authorization_code";
        HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create(TOKEN))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        TokenResponse token = mapper.readValue(send(tokenRequest), TokenResponse.class);
        if (token.accessToken() == null) throw new IllegalStateException("Google refused the sign-in code: " + token.error());
        HttpRequest userRequest = HttpRequest.newBuilder(URI.create(USERINFO))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token.accessToken())
                .GET()
                .build();
        UserInfo user = mapper.readValue(send(userRequest), UserInfo.class);
        if (user.sub() == null) throw new IllegalStateException("Google returned no account");
        String email = Boolean.TRUE.equals(user.emailVerified()) ? user.email() : null;
        return new GoogleUser(user.sub(), email, user.name(), user.picture());
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) throw new IllegalStateException("Google returned HTTP " + r.statusCode());
            return r.body();
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach Google", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Google", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
