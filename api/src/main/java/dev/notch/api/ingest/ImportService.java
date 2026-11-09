package dev.notch.api.ingest;

import dev.notch.api.config.ApiException;
import dev.notch.api.config.NotchProperties;
import dev.notch.api.crypto.ContentCipher;
import dev.notch.api.ingest.core.Chunk;
import dev.notch.api.ingest.core.Chunker;
import dev.notch.api.ingest.core.ContentHash;
import dev.notch.api.ingest.core.ParsedConversation;
import dev.notch.api.ingest.core.ParsedMessage;
import dev.notch.api.jobs.ImportProcessor;
import dev.notch.api.jobs.JobRepository;
import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectService;
import dev.notch.api.security.CurrentUser;
import dev.notch.api.usage.UsageService;
import dev.notch.engine.text.SecretScanner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The synchronous half of an import, run in one transaction:
 * read the file → redact secrets → encrypt and store new messages → split into chunks →
 * skip duplicate chunks (Bloom filter, then exact check) → queue the background job.
 *
 * <p>Embedding and memory extraction happen in the background job, so uploads return quickly.
 * If anything fails, the whole import rolls back, including the usage it consumed.
 */
@Service
public class ImportService {

    private final ProjectService projects;
    private final CurrentUser currentUser;
    private final UsageService usage;
    private final ImportFileReader reader;
    private final ImportRepository imports;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ChunkRepository chunks;
    private final ChunkDeduplicator dedup;
    private final JobRepository jobs;
    private final ContentCipher cipher;
    private final NotchProperties props;
    private final SecretScanner scanner = new SecretScanner();

    public ImportService(ProjectService projects, CurrentUser currentUser, UsageService usage,
                         ImportFileReader reader, ImportRepository imports,
                         ConversationRepository conversations, MessageRepository messages,
                         ChunkRepository chunks, ChunkDeduplicator dedup, JobRepository jobs,
                         ContentCipher cipher, NotchProperties props) {
        this.projects = projects;
        this.currentUser = currentUser;
        this.usage = usage;
        this.reader = reader;
        this.imports = imports;
        this.conversations = conversations;
        this.messages = messages;
        this.chunks = chunks;
        this.dedup = dedup;
        this.jobs = jobs;
        this.cipher = cipher;
        this.props = props;
    }

    /** Running totals for one import. */
    private static final class Counts {
        int conversationsAdded, conversationsUpdated, messagesAdded, messagesSkipped;
        int chunksAdded, duplicateChunks, bloomFalsePositives, secretsRedacted;
    }

    @Transactional
    public ImportReport importFile(UUID projectId, MultipartFile file, String requestedFormat) {
        Project project = projects.require(projectId);
        UUID userId = currentUser.id();
        byte[] content = readUpload(file);
        String filename = file.getOriginalFilename();

        usage.consume(userId, UsageService.IMPORT);
        ImportFileReader.Result parsed = reader.read(filename, content, requestedFormat);
        long messageCount = parsed.conversations().stream().mapToLong(c -> c.messages().size()).sum();
        if (messageCount == 0) {
            throw ApiException.badRequest("no_messages", "The file was read, but it has no messages to import.");
        }

        UUID importId = UUID.randomUUID();
        Instant now = Instant.now();
        // The import row must exist before messages reference it.
        imports.insert(report(importId, project, filename, parsed, "PROCESSING", new Counts(), now, null), userId);

        Counts counts = new Counts();
        Chunker chunker = new Chunker(props.ingest().maxChunkChars());
        String aad = userId.toString();
        for (ParsedConversation conv : parsed.conversations()) {
            storeConversation(project, userId, importId, parsed, conv, chunker, aad, counts, now);
        }

        boolean nothingNew = counts.messagesAdded == 0;
        String status = nothingNew ? "READY" : "PROCESSING";
        updateCounts(importId, counts, status, nothingNew);
        if (!nothingNew) jobs.enqueue(ImportProcessor.KIND, project.id(), importId);
        return report(importId, project, filename, parsed, status, counts, now, nothingNew ? Instant.now() : null);
    }

    private void storeConversation(Project project, UUID userId, UUID importId,
                                   ImportFileReader.Result parsed, ParsedConversation conv,
                                   Chunker chunker, String aad, Counts counts, Instant now) {
        String source = parsed.format().source();
        String title = scanner.scan(conv.title()).redacted();
        UUID convId = conversations.findId(project.id(), source, conv.externalId()).orElse(null);
        boolean existed = convId != null;
        if (!existed) {
            convId = UUID.randomUUID();
            Instant started = conv.startedAt() != null ? conv.startedAt() : firstSentAt(conv.messages(), now);
            conversations.insert(convId, project.id(), userId, source, conv.externalId(), title, started);
        }
        Set<String> known = existed ? messages.externalIds(convId) : Set.of();
        int seq = existed ? messages.nextSeq(convId) : 0;
        int added = 0;

        for (ParsedMessage m : conv.messages()) {
            if (known.contains(m.externalId())) {
                counts.messagesSkipped++;
                continue;
            }
            SecretScanner.Result scan = scanner.scan(m.text());
            counts.secretsRedacted += scan.findings().size();
            String clean = scan.redacted();
            UUID messageId = UUID.randomUUID();
            Instant sentAt = m.sentAt() != null ? m.sentAt() : now;
            messages.insert(messageId, convId, project.id(), userId, importId, m.externalId(), seq++,
                    m.role(), cipher.encrypt(clean, aad), clean.length(), sentAt);
            added++;
            storeChunks(project.id(), userId, messageId, clean, chunker, counts);
        }
        counts.messagesAdded += added;
        if (!existed) counts.conversationsAdded++;
        else if (added > 0) counts.conversationsUpdated++;
    }

    private void storeChunks(UUID projectId, UUID userId, UUID messageId, String text,
                             Chunker chunker, Counts counts) {
        for (Chunk c : chunker.split(text)) {
            byte[] hash = ContentHash.of(c.text());
            ChunkDeduplicator.Verdict verdict = dedup.check(projectId, hash);
            if (verdict == ChunkDeduplicator.Verdict.DUPLICATE) {
                counts.duplicateChunks++;
                continue;
            }
            if (verdict == ChunkDeduplicator.Verdict.FALSE_POSITIVE) counts.bloomFalsePositives++;
            boolean inserted = chunks.insert(UUID.randomUUID(), projectId, userId, messageId,
                    c.start(), c.end(), hash, c.estimatedTokens());
            if (inserted) {
                dedup.remember(projectId, hash);
                counts.chunksAdded++;
            } else {
                counts.duplicateChunks++;  // lost a race with a concurrent import
            }
        }
    }

    private static Instant firstSentAt(List<ParsedMessage> msgs, Instant fallback) {
        return msgs.stream().map(ParsedMessage::sentAt).filter(t -> t != null).min(Instant::compareTo).orElse(fallback);
    }

    private byte[] readUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("empty_file", "Choose a non-empty file to import.");
        }
        if (file.getSize() > props.ingest().maxFileBytes()) {
            throw ApiException.tooLarge("Files can be up to 5 MB. Split larger exports into smaller files.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("upload_failed", "The upload could not be read. Please try again.");
        }
    }

    private void updateCounts(UUID importId, Counts c, String status, boolean finished) {
        imports.updateCounts(importId, status, c.conversationsAdded, c.conversationsUpdated, c.messagesAdded,
                c.messagesSkipped, c.chunksAdded, c.duplicateChunks, c.bloomFalsePositives,
                c.secretsRedacted, finished);
    }

    private static ImportReport report(UUID id, Project p, String filename, ImportFileReader.Result parsed,
                                       String status, Counts c, Instant created, Instant finished) {
        return new ImportReport(id, p.id(), filename, parsed.format().source(), status,
                c.conversationsAdded, c.conversationsUpdated, c.messagesAdded, c.messagesSkipped,
                c.chunksAdded, c.duplicateChunks, c.bloomFalsePositives, c.secretsRedacted, 0,
                null, created, finished);
    }
}
