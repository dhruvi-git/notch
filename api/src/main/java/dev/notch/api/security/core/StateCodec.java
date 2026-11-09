package dev.notch.api.security.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * The OAuth {@code state} parameter, signed so the API needs no server-side storage:
 * {@code base64url(redirect).expiry.nonce.signature}, with an HMAC-SHA256 signature.
 *
 * <p>It protects the sign-in flow against forged callbacks (CSRF) and carries where to send
 * the user afterwards. Expired or tampered states are rejected.
 */
public final class StateCodec {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public StateCodec(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("NOTCH_AUTH_SECRET must be at least 32 characters. "
                    + "Generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String encode(String redirect, Instant expiresAt, String nonce) {
        if (nonce.contains(".")) throw new IllegalArgumentException("nonce must not contain '.'");
        String payload = B64.encodeToString(redirect.getBytes(StandardCharsets.UTF_8))
                + "." + expiresAt.getEpochSecond() + "." + nonce;
        return payload + "." + sign(payload);
    }

    /** The redirect carried by a valid, unexpired state; empty otherwise. */
    public Optional<String> verify(String state, Instant now) {
        if (state == null) return Optional.empty();
        int lastDot = state.lastIndexOf('.');
        if (lastDot <= 0) return Optional.empty();
        String payload = state.substring(0, lastDot);
        byte[] expected = sign(payload).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = state.substring(lastDot + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();  // constant time
        String[] parts = payload.split("\\.");
        if (parts.length != 3) return Optional.empty();
        try {
            long exp = Long.parseLong(parts[1]);
            if (now.getEpochSecond() >= exp) return Optional.empty();
            return Optional.of(new String(B64D.decode(parts[0]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return B64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
