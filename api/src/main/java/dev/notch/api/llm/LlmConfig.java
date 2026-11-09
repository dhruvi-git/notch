package dev.notch.api.llm;

import dev.notch.api.config.NotchProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Chooses the language-model and embedding clients. Only offline clients exist so far; a real
 * provider will be selected here by configuration, with no changes elsewhere.
 */
@Configuration
public class LlmConfig {

    public static final String OFFLINE = "offlineExtractor";

    @Bean
    @Primary
    public LlmClient llmClient() {
        return new HeuristicMemoryExtractor();
    }

    /** Always available, and always used for private projects. */
    @Bean(OFFLINE)
    public LlmClient offlineExtractor() {
        return new HeuristicMemoryExtractor();
    }

    @Bean
    public EmbeddingClient embeddingClient(NotchProperties props) {
        return new HashingEmbeddingClient(props.embedding().dimensions());
    }
}
