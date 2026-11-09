package dev.notch.api.crypto;

import dev.notch.api.config.NotchProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {

    /** Fails at startup, with instructions, when the key is missing. */
    @Bean
    public ContentCipher contentCipher(NotchProperties props) {
        return ContentCipher.fromBase64(props.crypto().key());
    }
}
