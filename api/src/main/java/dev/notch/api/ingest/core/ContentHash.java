package dev.notch.api.ingest.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * SHA-256 of normalized chunk text. Normalization (lower case, collapsed whitespace) makes
 * the same paragraph pasted with different spacing count as a duplicate.
 */
public final class ContentHash {

    private ContentHash() {
    }

    public static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public static byte[] of(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(normalize(text).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hex(byte[] hash) {
        return HexFormat.of().formatHex(hash);
    }
}
