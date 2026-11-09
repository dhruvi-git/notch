package dev.notch.api.security;

import dev.notch.api.config.NotchProperties;
import dev.notch.api.security.core.TokenCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates every {@code /api/**} request from its {@code Authorization: Bearer} token
 * and attaches the user id to the request. Everything else ({@code /auth/**}, health checks)
 * is public.
 *
 * <p>A missing or invalid token gets a 401 problem response straight from the filter, with
 * CORS headers so the web app can read it. In the local profile, a request with no token
 * acts as the seeded developer; a request with a bad token is still rejected.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String USER_ATTR = "notch.userId";
    public static final String TOKEN_ATTR = "notch.tokenHash";

    private final TokenRepository tokens;
    private final NotchProperties props;

    public AuthFilter(TokenRepository tokens, NotchProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || "OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);  // public paths and CORS preflight
            return;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).strip();
            if (TokenCodec.looksValid(token)) {
                byte[] hash = TokenCodec.hash(token);
                Optional<TokenRepository.ActiveToken> active = tokens.findActive(hash);
                if (active.isPresent()) {
                    request.setAttribute(USER_ATTR, active.get().userId());
                    request.setAttribute(TOKEN_ATTR, hash);
                    tokens.touch(active.get().id());
                    chain.doFilter(request, response);
                    return;
                }
            }
            reject(request, response, "invalid_token", "Your session has expired or the token was revoked. Sign in again.");
            return;
        }
        if (props.auth().devFallback()) {
            request.setAttribute(USER_ATTR, DevUser.ID);
            chain.doFilter(request, response);
            return;
        }
        reject(request, response, "sign_in_required", "Sign in to continue.");
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String code, String detail)
            throws IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && props.cors().allowedOrigins().contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
        }
        response.setStatus(401);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                + "\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}");
    }
}
