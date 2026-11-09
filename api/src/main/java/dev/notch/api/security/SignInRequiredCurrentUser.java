package dev.notch.api.security;

import dev.notch.api.config.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Outside local development, requests are rejected until GitHub sign-in is added (phase 2f).
 * This keeps a deployed build from ever serving data without authentication.
 */
@Component
@Profile("!local")
public class SignInRequiredCurrentUser implements CurrentUser {

    @Override
    public UUID id() {
        throw ApiException.signInRequired();
    }
}
