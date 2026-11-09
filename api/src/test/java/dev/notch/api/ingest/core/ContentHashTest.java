package dev.notch.api.ingest.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentHashTest {

    @Test
    void whitespaceAndCaseDifferencesHashEqually() {
        assertArrayEquals(ContentHash.of("Use  Postgres\n for billing"), ContentHash.of("use postgres for billing "));
    }

    @Test
    void differentTextHashesDifferently() {
        assertFalse(java.util.Arrays.equals(ContentHash.of("use postgres"), ContentHash.of("use mongo")));
    }

    @Test
    void hashIs32BytesAndHexIs64Chars() {
        byte[] h = ContentHash.of("x");
        assertEquals(32, h.length);
        assertEquals(64, ContentHash.hex(h).length());
    }
}
