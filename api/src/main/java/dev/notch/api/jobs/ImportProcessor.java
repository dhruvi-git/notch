package dev.notch.api.jobs;

import dev.notch.api.crypto.ContentCipher;
import dev.notch.api.ingest.ChunkRepository;
import dev.notch.api.ingest.ImportRepository;
import dev.notch.api.ingest.MessageRepository;
import dev.notch.api.llm.EmbeddingClient;
import dev.notch.api.llm.ExtractedMemory;
import dev.notch.api.llm.ExtractionRequest;
import dev.notch.api.llm.LlmClient;
import dev.notch.api.llm.LlmConfig;
import dev.notch.api.memory.MemoryCodec;
import dev.notch.api.memory.MemoryPayload;
import dev.notch.api.memory.MemoryRepository;
import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The background half of an import: embed the new chunks, then extract memories from the new
 * messages, conversation by conversation.
 *
 * <p>Private projects are never sent to an external model: they get no embeddings, and their
 * memories always come from the offline extractor.
 */
@Component
public class ImportProcessor {

    public static final String KIND = "PROCESS_IMPORT";
    static final int EMBED_BATCH = 64;

    private final ImportRepository imports;
    private final ProjectRepository projects;
    private final MessageRepository messages;
    private final ChunkRepository chunks;
    private final MemoryRepository memories;
    private final MemoryCodec codec;
    private final ContentCipher cipher;
    private final EmbeddingClient embeddings;
    private final LlmClient llm;
    private final LlmClient offline;

    public ImportProcessor(ImportRepository imports, ProjectRepository projects, MessageRepository messages,
                           ChunkRepository chunks, MemoryRepository memories, MemoryCodec codec,
                           ContentCipher cipher, EmbeddingClient embeddings, LlmClient llm,
                           @Qualifier(LlmConfig.OFFLINE) LlmClient offline) {
        this.imports = imports;
        this.projects = projects;
        this.messages = messages;
        this.chunks = chunks;
        this.memories = memories;
        this.codec = codec;
        this.cipher = cipher;
        this.embeddings = embeddings;
        this.llm = llm;
        this.offline = offline;
    }

    @Transactional
    public void process(Job job) {
        UUID userId = ownerOf(job);
        Project project = projects.find(job.projectId(), userId)
                .orElseThrow(() -> new IllegalStateException("project no longer exists"));
        String aad = userId.toString();

        List<MessageRepository.StoredMessage> stored = messages.forImport(job.importId(), userId);
        Map<UUID, String> text = new HashMap<>();
        for (MessageRepository.StoredMessage m : stored) text.put(m.id(), cipher.decrypt(m.body(), aad));

        if (!project.isPrivate()) embedChunks(job.importId(), userId, text);
        memories.deleteForImport(job.importId(), userId);  // makes retries idempotent
        String source = imports.find(job.importId(), job.projectId(), userId).map(dev.notch.api.ingest.ImportReport::format).orElse(null);
        int extracted = extractMemories(project, userId, stored, text, source);
        imports.markReady(job.importId(), extracted);
    }

    private void embedChunks(UUID importId, UUID userId, Map<UUID, String> text) {
        List<ChunkRepository.PendingChunk> pending = chunks.withoutEmbedding(importId, userId);
        for (int from = 0; from < pending.size(); from += EMBED_BATCH) {
            List<ChunkRepository.PendingChunk> batch = pending.subList(from, Math.min(pending.size(), from + EMBED_BATCH));
            List<String> inputs = new ArrayList<>(batch.size());
            for (ChunkRepository.PendingChunk c : batch) {
                inputs.add(text.get(c.messageId()).substring(c.start(), c.end()));
            }
            List<float[]> vectors = embeddings.embed(inputs);
            for (int i = 0; i < batch.size(); i++) {
                float[] v = vectors.get(i);
                chunks.setEmbedding(batch.get(i).id(), v, embeddings.name());
            }
        }
    }

    /** A captured session with fewer of the person's messages than this is a check-in, not work to mine. */
    static final int MIN_USER_MESSAGES = 2;

    private int extractMemories(Project project, UUID userId, List<MessageRepository.StoredMessage> stored,
                                Map<UUID, String> text, String source) {
        LlmClient extractor = project.isPrivate() ? offline : llm;
        java.util.Set<String> known = knownMemories(project, userId);
        // What the project already holds, under short references, so extraction can replace and complete.
        List<ExtractionRequest.Existing> existing = new ArrayList<>();
        Map<String, UUID> refIds = new HashMap<>();
        int d = 0;
        for (MemoryRepository.StoredMemory m : memories.list(project.id(), userId, "DECISION", "ACTIVE", 40)) {
            String ref = "D" + (++d);
            refIds.put(ref, m.id());
            existing.add(new ExtractionRequest.Existing(ref, dev.notch.api.llm.MemoryKind.DECISION, codec.open(m.payload(), userId).title()));
        }
        int t = 0;
        for (MemoryRepository.StoredMemory m : memories.list(project.id(), userId, "TASK", "ACTIVE", 40)) {
            String ref = "T" + (++t);
            refIds.put(ref, m.id());
            existing.add(new ExtractionRequest.Existing(ref, dev.notch.api.llm.MemoryKind.TASK, codec.open(m.payload(), userId).title()));
        }
        boolean commits = "git".equals(source);
        Map<UUID, List<MessageRepository.StoredMessage>> byConversation = new LinkedHashMap<>();
        for (MessageRepository.StoredMessage m : stored) {
            byConversation.computeIfAbsent(m.conversationId(), k -> new ArrayList<>()).add(m);
        }
        int count = 0;
        for (List<MessageRepository.StoredMessage> conv : byConversation.values()) {
            // A one-question session ("where did we stop?") is searchable, but its answer restates
            // other records and goes stale; mining it adds noise to the Ledger.
            if ("claude-code".equals(source) && messages.userMessages(conv.get(0).conversationId()) < MIN_USER_MESSAGES) continue;
            List<ExtractionRequest.SourceMessage> sourceMessages = new ArrayList<>(conv.size());
            for (MessageRepository.StoredMessage m : conv) {
                sourceMessages.add(new ExtractionRequest.SourceMessage(m.role(), text.get(m.id())));
            }
            ExtractionRequest request = new ExtractionRequest(conv.get(0).conversationTitle(), sourceMessages, existing);
            for (ExtractedMemory e : extractor.extractMemories(request)) {
                if (e.messageIndex() >= conv.size()) continue;  // ignore spans outside this request
                if (e.completes() != null) {
                    UUID task = refIds.get(e.completes());
                    if (task != null) memories.updateStatus(task, userId, "DONE");  // the session shows it finished
                    continue;
                }
                // Commit messages say what changed; only explicit choices and fixes are worth keeping from them.
                if (commits && e.kind() != dev.notch.api.llm.MemoryKind.DECISION && e.kind() != dev.notch.api.llm.MemoryKind.FIX) continue;
                if (!known.add(memoryKey(e.kind().name(), e.title()))) continue;  // already in the ledger
                MessageRepository.StoredMessage src = conv.get(e.messageIndex());
                int len = text.get(src.id()).length();
                int start = Math.min(e.start(), len);
                int end = Math.min(e.end(), len);
                MemoryPayload payload = new MemoryPayload(e.title(), e.body(), e.rationale(), e.rejectedOptions());
                Instant createdAt = src.sentAt() != null ? src.sentAt() : Instant.now();
                UUID id = UUID.randomUUID();
                memories.insert(id, project.id(), userId, e.kind().name(),
                        codec.seal(payload, userId), src.id(), start, end, createdAt);
                if (e.replaces() != null && refIds.containsKey(e.replaces())) {
                    memories.supersede(refIds.get(e.replaces()), id, userId);  // the Ledger keeps the old one as history
                }
                count++;
            }
        }
        return count;
    }

    /**
     * Memories the project already has, so the same decision mentioned in several chats (or a
     * pasted copy of one) is recorded once.
     */
    private java.util.Set<String> knownMemories(Project project, UUID userId) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (MemoryRepository.StoredMemory m : memories.list(project.id(), userId, null, null, 5000)) {
            keys.add(memoryKey(m.kind(), codec.open(m.payload(), userId).title()));
        }
        return keys;
    }

    static String memoryKey(String kind, String title) {
        return kind + ":" + title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private UUID ownerOf(Job job) {
        return imports.ownerOf(job.importId())
                .orElseThrow(() -> new IllegalStateException("import no longer exists"));
    }
}
