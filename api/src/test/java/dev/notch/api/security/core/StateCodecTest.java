package dev.notch.api.security.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class StateCodecTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-characters";
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private final StateCodec codec = new StateCodec(SECRET);

    @Test
    void roundTripsTheRedirect() {
        String redirect = "https://notch.app/auth/callback?next=/p/1.2";
        String state = codec.encode(redirect, NOW.plus(Duration.ofMinutes(10)), "n0nce");
        assertEquals(redirect, codec.verify(state, NOW).orElseThrow());
    }

    @Test
    void rejectsExpiredState() {  // boundary: exactly at expiry
        String state = codec.encode("https://notch.app/cb", NOW, "n");
        assertTrue(codec.verify(state, NOW).isEmpty());
    }

    @Test
    void rejectsTamperedState() {
        String state = codec.encode("https://notch.app/cb", NOW.plus(Duration.ofMinutes(5)), "n");
        String evil = state.replaceFirst("^[^.]+", java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("https://evil.example/cb".getBytes()));
        assertTrue(codec.verify(evil, NOW).isEmpty());
        assertTrue(codec.verify(state + "x", NOW).isEmpty());
    }

    @Test
    void rejectsStateSignedWithAnotherSecret() {
        String state = new StateCodec("another-secret-that-is-also-32-characters!").encode("https://notch.app/cb",
                NOW.plus(Duration.ofMinutes(5)), "n");
        assertTrue(codec.verify(state, NOW).isEmpty());
    }

    @Test
    void rejectsGarbageAndShortSecrets() {
        assertTrue(codec.verify(null, NOW).isEmpty());
        assertTrue(codec.verify("not-a-state", NOW).isEmpty());
        assertTrue(codec.verify("a.b.c.d.e", NOW).isEmpty());
        assertThrows(IllegalStateException.class, () -> new StateCodec("short"));
    }
}
