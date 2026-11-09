package dev.notch.api.security.core;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class TokenCodecTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    void issuesPrefixedUniqueTokens() {
        TokenCodec.Issued a = TokenCodec.issue(TokenCodec.PERSONAL_PREFIX, random);
        TokenCodec.Issued b = TokenCodec.issue(TokenCodec.PERSONAL_PREFIX, random);
        assertTrue(a.token().startsWith("ntch_pat_"));
        assertNotEquals(a.token(), b.token());
        assertEquals(TokenCodec.PERSONAL_PREFIX.length() + 43, a.token().length());  // 32 bytes base64url
        assertEquals(a.token().substring(0, 13), a.displayPrefix());
    }

    @Test
    void storedHashMatchesTheToken() {
        TokenCodec.Issued t = TokenCodec.issue(TokenCodec.SESSION_PREFIX, random);
        assertArrayEquals(t.hash(), TokenCodec.hash(t.token()));
        assertEquals(32, t.hash().length);
    }

    @Test
    void shapeCheckAcceptsIssuedTokens() {
        assertTrue(TokenCodec.looksValid(TokenCodec.issue(TokenCodec.SESSION_PREFIX, random).token()));
        assertTrue(TokenCodec.looksValid(TokenCodec.issue(TokenCodec.CODE_PREFIX, random).token()));
    }

    @Test
    void shapeCheckRejectsJunk() {  // boundary / invalid
        assertFalse(TokenCodec.looksValid(null));
        assertFalse(TokenCodec.looksValid(""));
        assertFalse(TokenCodec.looksValid("Bearer abc"));
        assertFalse(TokenCodec.looksValid("ntch_" + "a".repeat(100)));
        assertFalse(TokenCodec.looksValid("ntch_ses_" + "a".repeat(30) + "!!!"));
        assertFalse(TokenCodec.looksValid("ghp_" + "a".repeat(40)));
    }
}
