package dev.notch.api.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ContentCipherTest {

    private static final byte[] KEY = new byte[32];
    static {
        for (int i = 0; i < 32; i++) KEY[i] = (byte) i;
    }

    @Test
    void roundTripsText() {
        ContentCipher c = new ContentCipher(KEY);
        String text = "We chose Postgres because of pgvector. Ünïcode ✓";
        assertEquals(text, c.decrypt(c.encrypt(text, "user-1"), "user-1"));
    }

    @Test
    void sameTextEncryptsDifferentlyEachTime() {
        ContentCipher c = new ContentCipher(KEY);
        assertFalse(java.util.Arrays.equals(c.encrypt("same", "u"), c.encrypt("same", "u")));
    }

    @Test
    void ciphertextIsBoundToItsOwner() {
        ContentCipher c = new ContentCipher(KEY);
        byte[] blob = c.encrypt("secret plan", "alice");
        assertThrows(IllegalStateException.class, () -> c.decrypt(blob, "bob"));
    }

    @Test
    void tamperingIsDetected() {
        ContentCipher c = new ContentCipher(KEY);
        byte[] blob = c.encrypt("hello", "u");
        blob[blob.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> c.decrypt(blob, "u"));
    }

    @Test
    void emptyTextRoundTrips() {  // boundary
        ContentCipher c = new ContentCipher(KEY);
        assertEquals("", c.decrypt(c.encrypt("", "u"), "u"));
    }

    @Test
    void rejectsBadKeysAndBlobs() {
        assertThrows(IllegalArgumentException.class, () -> new ContentCipher(new byte[16]));
        assertThrows(IllegalStateException.class, () -> ContentCipher.fromBase64(" "));
        assertThrows(IllegalStateException.class, () -> ContentCipher.fromBase64("not base64!!"));
        ContentCipher c = ContentCipher.fromBase64(Base64.getEncoder().encodeToString(KEY));
        assertThrows(IllegalArgumentException.class, () -> c.decrypt(new byte[5], "u"));
    }
}
