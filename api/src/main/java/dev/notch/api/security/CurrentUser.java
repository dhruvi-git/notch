package dev.notch.api.security;

import java.util.UUID;

/**
 * The signed-in user for the current request. Every repository query takes this id, so data
 * is always scoped to its owner.
 */
public interface CurrentUser {
    UUID id();
}
