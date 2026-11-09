package dev.notch.api.security;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local development only: a session token without GitHub, for building the web app offline
 * and for the cross-user isolation test. Absent from every other profile.
 */
@RestController
@Profile("local")
public class DevAuthController {

    public record DevLoginRequest(String handle) {
    }

    private final AuthService auth;

    public DevAuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/auth/dev-login")
    public AuthService.Session devLogin(@RequestBody(required = false) DevLoginRequest req) {
        return auth.devLogin(req == null ? null : req.handle());
    }
}
