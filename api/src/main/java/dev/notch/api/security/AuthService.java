package dev.notch.api.security;

import dev.notch.api.config.ApiException;
import dev.notch.api.config.NotchProperties;
import dev.notch.api.security.core.RedirectPolicy;
import dev.notch.api.security.core.StateCodec;
import dev.notch.api.security.core.TokenCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * GitHub sign-in and tokens.
 *
 * <ol>
 *   <li>{@link #startUrl}: the web app sends the user here with its callback URL; we redirect
 *       to GitHub with a signed state.</li>
 *   <li>{@link #finish}: GitHub returns with a code; we verify the state, read the GitHub
 *       user, and send the browser back to the web app with a one-time code.</li>
 *   <li>{@link #exchange}: the web app trades that code (valid 60 seconds, usable once) for a
 *       session token.</li>
 * </ol>
 */
@Service
public class AuthService {

    static final Duration STATE_TTL = Duration.ofMinutes(10);
    static final Duration CODE_TTL = Duration.ofSeconds(60);
    static final int MAX_PERSONAL_TOKENS = 10;
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public record Session(String token, Instant expiresAt, UserRepository.User user) {
    }

    public record NewToken(UUID id, String name, String token, String prefix, Instant createdAt) {
    }

    private final NotchProperties props;
    private final StateCodec state;
    private final RedirectPolicy redirects;
    private final GitHubClient github;
    private final GoogleClient google;
    private final UserRepository users;
    private final TokenRepository tokens;
    private final SecureRandom random = new SecureRandom();

    public AuthService(NotchProperties props, GitHubClient github, UserRepository users, TokenRepository tokens,
                       GoogleClient google) {
        this.google = google;
        this.props = props;
        this.state = new StateCodec(props.auth().stateSecret());
        this.redirects = new RedirectPolicy(props.cors().allowedOrigins());
        this.github = github;
        this.users = users;
        this.tokens = tokens;
    }

    public boolean githubConfigured() {
        return props.auth().github().configured();
    }

    public String startUrl(String redirect) {
        if (!redirects.allows(redirect)) {
            throw ApiException.badRequest("invalid_redirect", "That redirect address is not allowed.");
        }
        if (!githubConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "github_not_configured",
                    "GitHub sign-in isn't set up on this server.");
        }
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String s = state.encode(redirect, Instant.now().plus(STATE_TTL),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        NotchProperties.Github gh = props.auth().github();
        return "https://github.com/login/oauth/authorize?client_id=" + enc(gh.clientId())
                + "&redirect_uri=" + enc(gh.callbackUrl()) + "&scope=read%3Auser&state=" + enc(s);
    }

    /** Where to send the browser after GitHub returns: the web app, with a code or an error. */
    public String finish(String code, String stateParam, String error) {
        String redirect = state.verify(stateParam, Instant.now())
                .orElseThrow(() -> ApiException.badRequest("invalid_state",
                        "This sign-in link has expired or was tampered with. Start again from the app."));
        if (!redirects.allows(redirect)) throw ApiException.badRequest("invalid_redirect", "That redirect address is not allowed.");
        if (error != null && !error.isBlank()) return withParam(redirect, "error", error);
        if (code == null || code.isBlank()) return withParam(redirect, "error", "missing_code");
        try {
            GitHubClient.GitHubUser gh = github.signIn(code);
            UUID userId = users.upsertGithub(gh.id(), gh.login(), gh.avatarUrl());
            TokenCodec.Issued oneTime = TokenCodec.issue(TokenCodec.CODE_PREFIX, random);
            tokens.insertAuthCode(oneTime.hash(), userId, Instant.now().plus(CODE_TTL));
            return withParam(redirect, "code", oneTime.token());
        } catch (RuntimeException e) {
            log.warn("GitHub sign-in failed: {}", e.getMessage());
            return withParam(redirect, "error", "github_failed");
        }
    }

    // ------------------------------------------------------------------ Google

    public boolean googleConfigured() {
        return props.auth().google().configured();
    }

    public String googleStartUrl(String redirect) {
        if (!redirects.allows(redirect)) {
            throw ApiException.badRequest("invalid_redirect", "That redirect address is not allowed.");
        }
        if (!googleConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "google_not_configured",
                    "Google sign-in isn't set up on this server.");
        }
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String s = state.encode(redirect, Instant.now().plus(STATE_TTL),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        NotchProperties.Google g = props.auth().google();
        return HttpGoogleClient.AUTHORIZE + "?client_id=" + enc(g.clientId()) + "&redirect_uri=" + enc(g.callbackUrl())
                + "&response_type=code&scope=" + enc("openid email profile") + "&prompt=select_account&state=" + enc(s);
    }

    /** Where to send the browser after Google returns: the web app, with a code or an error. */
    public String googleFinish(String code, String stateParam, String error) {
        String redirect = state.verify(stateParam, Instant.now())
                .orElseThrow(() -> ApiException.badRequest("invalid_state",
                        "This sign-in link has expired or was tampered with. Start again from the app."));
        if (!redirects.allows(redirect)) throw ApiException.badRequest("invalid_redirect", "That redirect address is not allowed.");
        if (error != null && !error.isBlank()) return withParam(redirect, "error", error);
        if (code == null || code.isBlank()) return withParam(redirect, "error", "missing_code");
        try {
            GoogleClient.GoogleUser g = google.signIn(code);
            UUID userId = users.upsertGoogle(g.sub(), handleFor(g), g.email(), g.pictureUrl());
            TokenCodec.Issued oneTime = TokenCodec.issue(TokenCodec.CODE_PREFIX, random);
            tokens.insertAuthCode(oneTime.hash(), userId, Instant.now().plus(CODE_TTL));
            return withParam(redirect, "code", oneTime.token());
        } catch (RuntimeException e) {
            log.warn("Google sign-in failed: {}", e.getMessage());
            return withParam(redirect, "error", "google_failed");
        }
    }

    /** The name shown in the app: the person's name, else the part of their email before the @. */
    static String handleFor(GoogleClient.GoogleUser g) {
        if (g.name() != null && !g.name().isBlank()) return g.name().strip();
        if (g.email() != null && g.email().contains("@")) return g.email().substring(0, g.email().indexOf('@'));
        return "Google user";
    }

    public Session exchange(String code) {
        if (!TokenCodec.looksValid(code)) throw ApiException.badRequest("invalid_code", "That sign-in code is not valid.");
        UUID userId = tokens.consumeAuthCode(TokenCodec.hash(code))
                .orElseThrow(() -> ApiException.badRequest("invalid_code",
                        "That sign-in code has expired or was already used. Sign in again."));
        return newSession(userId);
    }

    /** Local development only. */
    public Session devLogin(String handle) {
        String h = handle == null || handle.isBlank() ? "dev" : handle.strip();
        if (!h.matches("[A-Za-z0-9_-]{1,39}")) throw ApiException.badRequest("invalid_handle", "Use letters, digits, - or _.");
        UUID userId = h.equals("dev") ? DevUser.ID : users.findOrCreateDev(h);
        return newSession(userId);
    }

    public void logout(byte[] tokenHash) {
        if (tokenHash != null) tokens.revokeByHash(tokenHash);
    }

    public NewToken createPersonalToken(UUID userId, String name) {
        if (tokens.countPersonal(userId) >= MAX_PERSONAL_TOKENS) {
            throw ApiException.conflict("too_many_tokens", "You have " + MAX_PERSONAL_TOKENS
                    + " tokens already. Revoke one you no longer use first.");
        }
        TokenCodec.Issued t = TokenCodec.issue(TokenCodec.PERSONAL_PREFIX, random);
        UUID id = UUID.randomUUID();
        tokens.insert(id, userId, name.strip(), t.hash(), t.displayPrefix(), "personal", null);
        return new NewToken(id, name.strip(), t.token(), t.displayPrefix(), Instant.now());
    }

    public List<TokenRepository.TokenInfo> listPersonalTokens(UUID userId) {
        return tokens.listPersonal(userId);
    }

    public void revokePersonalToken(UUID userId, UUID tokenId) {
        if (!tokens.revokePersonal(tokenId, userId)) throw ApiException.notFound("Token");
    }

    public UserRepository.User user(UUID userId) {
        return users.find(userId).orElseThrow(ApiException::signInRequired);
    }

    private Session newSession(UUID userId) {
        TokenCodec.Issued t = TokenCodec.issue(TokenCodec.SESSION_PREFIX, random);
        Instant expires = Instant.now().plus(Duration.ofDays(props.auth().sessionDays()));
        tokens.insert(UUID.randomUUID(), userId, "web session", t.hash(), t.displayPrefix(), "session", expires);
        return new Session(t.token(), expires, user(userId));
    }

    private static String withParam(String url, String name, String value) {
        return url + (url.contains("?") ? "&" : "?") + name + "=" + enc(value);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
