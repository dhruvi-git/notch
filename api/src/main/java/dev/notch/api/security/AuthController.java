package dev.notch.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/** Public sign-in endpoints. */
@RestController
public class AuthController {

    public record ExchangeRequest(@NotBlank @Size(max = 100) String code) {
    }

    private final AuthService auth;
    private final dev.notch.api.config.NotchProperties props;

    public AuthController(AuthService auth, dev.notch.api.config.NotchProperties props) {
        this.auth = auth;
        this.props = props;
    }

    /** Lets the web app show only the sign-in options this server supports. */
    @GetMapping("/auth/config")
    public Map<String, Object> config() {
        return Map.of("github", auth.githubConfigured(), "devLogin", props.auth().devFallback());
    }

    @GetMapping("/auth/github/start")
    public void start(@RequestParam String redirect, HttpServletResponse response) throws IOException {
        response.sendRedirect(auth.startUrl(redirect));
    }

    @GetMapping("/auth/github/callback")
    public void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error, HttpServletResponse response) throws IOException {
        response.sendRedirect(auth.finish(code, state, error));
    }

    @PostMapping("/auth/exchange")
    public AuthService.Session exchange(@Valid @RequestBody ExchangeRequest req) {
        return auth.exchange(req.code());
    }

    /** Revokes the session token sent with this request. */
    @PostMapping("/api/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        auth.logout((byte[]) request.getAttribute(AuthFilter.TOKEN_ATTR));
    }
}
