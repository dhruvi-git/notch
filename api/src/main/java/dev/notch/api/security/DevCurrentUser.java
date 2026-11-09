package dev.notch.api.security;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Local development only: every request acts as one seeded developer account. */
@Component
@Profile("local")
public class DevCurrentUser implements CurrentUser {

    public static final UUID DEV_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Override
    public UUID id() {
        return DEV_USER_ID;
    }
}
