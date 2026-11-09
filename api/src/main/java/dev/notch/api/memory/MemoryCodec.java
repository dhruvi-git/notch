package dev.notch.api.memory;

import dev.notch.api.crypto.ContentCipher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Encrypts memory payloads as JSON, bound to their owner, and reads them back. */
@Component
public class MemoryCodec {

    private final ObjectMapper mapper;
    private final ContentCipher cipher;

    public MemoryCodec(ObjectMapper mapper, ContentCipher cipher) {
        this.mapper = mapper;
        this.cipher = cipher;
    }

    public byte[] seal(MemoryPayload payload, UUID userId) {
        String json = new String(mapper.writeValueAsBytes(payload), StandardCharsets.UTF_8);
        return cipher.encrypt(json, userId.toString());
    }

    public MemoryPayload open(byte[] sealed, UUID userId) {
        String json = cipher.decrypt(sealed, userId.toString());
        return mapper.readValue(json.getBytes(StandardCharsets.UTF_8), MemoryPayload.class);
    }
}
