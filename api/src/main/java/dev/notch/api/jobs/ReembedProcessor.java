package dev.notch.api.jobs;

import dev.notch.api.crypto.ContentCipher;
import dev.notch.api.ingest.ChunkRepository;
import dev.notch.api.llm.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Re-embeds a project's passages after the embedding model changes (offline hashing to Voyage,
 * say), a batch per job, until every vector comes from the current model. Started for every
 * project that needs it when the API starts. Private projects are never embedded.
 */
@Component
public class ReembedProcessor {

    public static final String KIND = "reembed";
    static final int BATCH = 256;

    private static final Logger log = LoggerFactory.getLogger(ReembedProcessor.class);

    private final ChunkRepository chunks;
    private final EmbeddingClient embeddings;
    private final ContentCipher cipher;
    private final JobRepository jobs;

    public ReembedProcessor(ChunkRepository chunks, EmbeddingClient embeddings, ContentCipher cipher, JobRepository jobs) {
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.cipher = cipher;
        this.jobs = jobs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startIfNeeded() {
        List<UUID> stale = chunks.projectsWithStaleEmbeddings(embeddings.name());
        int queued = 0;
        for (UUID project : stale) {
            if (!jobs.hasOpen(KIND, project)) {
                jobs.enqueue(KIND, project, null);
                queued++;
            }
        }
        if (queued > 0) log.info("Re-embedding {} project(s) with {}", queued, embeddings.name());
    }

    public void process(Job job) {
        List<ChunkRepository.StaleChunk> batch = chunks.staleEmbeddings(job.projectId(), embeddings.name(), BATCH);
        if (batch.isEmpty()) return;
        List<String> texts = new ArrayList<>(batch.size());
        for (ChunkRepository.StaleChunk c : batch) {
            String body = cipher.decrypt(c.body(), c.userId().toString());
            texts.add(body.substring(Math.min(c.start(), body.length()), Math.min(c.end(), body.length())));
        }
        List<float[]> vectors = embeddings.embed(texts);
        for (int i = 0; i < batch.size(); i++) chunks.setEmbedding(batch.get(i).id(), vectors.get(i), embeddings.name());
        if (batch.size() == BATCH) jobs.enqueue(KIND, job.projectId(), null);  // more to do: the next batch
    }
}
