package dev.notch.api.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * AES-256-GCM encryption for content at rest.
 *
 * <p>Layout of a ciphertext: one version byte, a 12-byte random IV, then the GCM output
 * (ciphertext plus 16-byte tag). The owner's id is passed as associated data, so a ciphertext
 * copied into another user's row fails to decrypt.
 */
public final class ContentCipher {

    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public ContentCipher(byte[] rawKey) {
        Objects.requireNonNull(rawKey, "rawKey");
        if (rawKey.length != 32) {
            throw new IllegalArgumentException("AES-256 needs a 32-byte key, got " + rawKey.length);
        }
        this.key = new SecretKeySpec(rawKey.clone(), "AES");
    }

    /** Builds a cipher from a base64 key, failing clearly when it is missing. */
    public static ContentCipher fromBase64(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "NOTCH_CRYPTO_KEY is not set. Generate one with: openssl rand -base64 32");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("NOTCH_CRYPTO_KEY is not valid base64", e);
        }
        return new ContentCipher(raw);
    }

    public byte[] encrypt(String plaintext, String associatedData) {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(associatedData, "associatedData");
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + sealed.length)
                    .put(VERSION).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(byte[] blob, String associatedData) {
        Objects.requireNonNull(blob, "blob");
        Objects.requireNonNull(associatedData, "associatedData");
        if (blob.length < 1 + IV_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("ciphertext too short");
        }
        if (blob[0] != VERSION) {
            throw new IllegalArgumentException("unknown ciphertext version " + blob[0]);
        }
        try {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES));
            c.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] plain = c.doFinal(blob, 1 + IV_BYTES, blob.length - 1 - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("decryption failed: wrong key, owner or tampered data", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }
}
