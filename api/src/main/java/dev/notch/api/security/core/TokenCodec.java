package dev.notch.api.security.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Bearer tokens: 32 random bytes, base64url, behind a readable prefix ({@code ntch_ses_} for
 * web sessions, {@code ntch_pat_} for personal tokens used by the extensions).
 *
 * <p>Only the SHA-256 hash is stored. A slow password hash is unnecessary because the tokens
 * carry 256 bits of randomness; a database leak still reveals no usable token.
 */
public final class TokenCodec {

    public static final String SESSION_PREFIX = "ntch_ses_";
    public static final String PERSONAL_PREFIX = "ntch_pat_";
    public static final String CODE_PREFIX = "ntch_code_";
    static final int RANDOM_BYTES = 32;

    /** A new token: the secret (shown once), its hash (stored) and a short prefix for display. */
    public record Issued(String token, byte[] hash, String displayPrefix) {
    }

    private TokenCodec() {
    }

    public static Issued issue(String prefix, SecureRandom random) {
        byte[] raw = new byte[RANDOM_BYTES];
        random.nextBytes(raw);
        String token = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new Issued(token, hash(token), token.substring(0, prefix.length() + 4));
    }

    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A cheap shape check before touching the database. */
    public static boolean looksValid(String token) {
        if (token == null || token.length() < 40 || token.length() > 80 || !token.startsWith("ntch_")) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
