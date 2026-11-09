package dev.notch.api.security;

import java.util.UUID;

/** The seeded local-development account, used when the local profile allows tokenless requests. */
public final class DevUser {

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private DevUser() {
    }
}
