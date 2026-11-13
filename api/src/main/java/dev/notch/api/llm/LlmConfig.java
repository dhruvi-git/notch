package dev.notch.api.llm;

import dev.notch.api.config.NotchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Chooses the language-model and embedding clients from configuration. "auto" uses a provider
 * when its API key is set and stays offline otherwise, so a fresh checkout runs without keys.
 */
@Configuration
public class LlmConfig {

    public static final String OFFLINE = "offlineLlmClient";

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    @Primary
    public LlmClient llmClient(NotchProperties props) {
        NotchProperties.Llm llm = props.llm();
        String key = llm == null ? null : llm.apiKey();
        if (ProviderChoice.use(llm == null ? null : llm.provider(), "anthropic", key, "ANTHROPIC_API_KEY")) {
            AnthropicLlmClient.Config config = new AnthropicLlmClient.Config(key, llm.model(), null, null, llm.effort(), null);
            AnthropicLlmClient client = new AnthropicLlmClient(config, new OfflineLlmClient());
            log.info("Answers and extraction: Claude ({}, effort {}), offline for private projects",
                    client.name(), config.effort() == null ? "not set" : config.effort());
            return client;
        }
        log.info("Answers and extraction: offline");
        return new OfflineLlmClient();
    }

    /** Always available, and always used for private projects. */
    @Bean(OFFLINE)
    public LlmClient offlineLlmClient() {
        return new OfflineLlmClient();
    }

    @Bean
    public EmbeddingClient embeddingClient(NotchProperties props) {
        NotchProperties.Embedding e = props.embedding();
        if (ProviderChoice.use(e.provider(), "voyage", e.apiKey(), "VOYAGE_API_KEY")) {
            VoyageEmbeddingClient client = new VoyageEmbeddingClient(
                    new VoyageEmbeddingClient.Config(e.apiKey(), e.model(), e.dimensions(), null, null));
            log.info("Embeddings: {}", client.name());
            return client;
        }
        log.info("Embeddings: offline word hashing");
        return new HashingEmbeddingClient(e.dimensions());
    }

}
