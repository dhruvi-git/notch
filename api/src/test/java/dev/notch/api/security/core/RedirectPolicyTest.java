package dev.notch.api.security.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedirectPolicyTest {

    private final RedirectPolicy policy = new RedirectPolicy(List.of("http://localhost:3000", "https://Notch.app"));

    @Test
    void allowsConfiguredOriginsWithAnyPath() {
        assertTrue(policy.allows("http://localhost:3000/auth/callback"));
        assertTrue(policy.allows("https://notch.app/auth/callback?x=1"));
    }

    @Test
    void rejectsOtherHostsPortsAndSchemes() {
        assertFalse(policy.allows("https://evil.example/auth/callback"));
        assertFalse(policy.allows("http://localhost:3001/auth/callback"));
        assertFalse(policy.allows("http://notch.app/auth/callback"));
        assertFalse(policy.allows("javascript:alert(1)"));
    }

    @Test
    void rejectsLookalikesAndUserInfoTricks() {
        assertFalse(policy.allows("https://notch.app.evil.example/cb"));
        assertFalse(policy.allows("https://notch.app@evil.example/cb"));
        assertFalse(policy.allows("https://evil.example@notch.app/cb"));
    }

    @Test
    void rejectsBlankAndMalformed() {  // boundary
        assertFalse(policy.allows(null));
        assertFalse(policy.allows(""));
        assertFalse(policy.allows("not a url"));
        assertFalse(policy.allows("/relative/path"));
    }
}
