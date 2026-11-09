package dev.notch.api.search.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KeyExtractorTest {

    @Test
    void findsPathsIdentifiersAndErrors() {
        List<String> keys = KeyExtractor.autocompleteKeys(
                "In src/api/AuthService.java the refreshToken call throws NullPointerException; see `retry_with_backoff`.");
        assertTrue(keys.contains("src/api/AuthService.java"));
        assertTrue(keys.contains("refreshToken"));
        assertTrue(keys.contains("NullPointerException"));
        assertTrue(keys.contains("retry_with_backoff"));
    }

    @Test
    void linkKeysIgnoreOrdinaryIdentifiers() {
        Set<String> keys = KeyExtractor.linkKeys("refreshToken failed with ECONNREFUSED in web/app.tsx");
        assertEquals(Set.of("ECONNREFUSED", "web/app.tsx"), keys);
    }

    @Test
    void plainProseHasNoKeys() {  // boundary
        assertTrue(KeyExtractor.autocompleteKeys("We talked about the plan for next week.").isEmpty());
        assertTrue(KeyExtractor.linkKeys("").isEmpty());
    }

    @Test
    void urlsAndVersionNumbersAreNotPaths() {
        assertTrue(KeyExtractor.linkKeys("version 1.2.3 is out").isEmpty());
    }
}
