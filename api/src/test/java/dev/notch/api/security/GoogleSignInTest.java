package dev.notch.api.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoogleSignInTest {

    @Test
    void theNameShownIsTheirNameThenTheirEmailBeforeTheAt() {
        assertEquals("Ada Lovelace", AuthService.handleFor(new GoogleClient.GoogleUser("1", "ada@example.com", " Ada Lovelace ", null)));
        assertEquals("ada", AuthService.handleFor(new GoogleClient.GoogleUser("1", "ada@example.com", "", null)));
        assertEquals("Google user", AuthService.handleFor(new GoogleClient.GoogleUser("1", null, null, null)));
    }
}
