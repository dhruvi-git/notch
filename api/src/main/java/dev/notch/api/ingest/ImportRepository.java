package dev.notch.api.ingest;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ImportRepository {

    private static final RowMapper<ImportReport> ROW = (rs, n) -> new ImportReport(
            Db.uuid(rs, "id"), Db.uuid(rs, "project_id"), rs.getString("filename"),
            rs.getString("format"), rs.getString("status"),
            rs.getInt("conversations_added"), rs.getInt("conversations_updated"),
            rs.getInt("messages_added"), rs.getInt("messages_skipped"), rs.getInt("chunks_added"),
            rs.getInt("duplicate_chunks"), rs.getInt("bloom_false_positives"),
            rs.getInt("secrets_redacted"), rs.getInt("memories_extracted"), rs.getString("error"),
            Db.instant(rs, "created_at"), Db.instant(rs, "finished_at"));

    private final JdbcClient jdbc;

    public ImportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ImportReport r, UUID userId) {
        jdbc.sql("""
                INSERT INTO imports (id, project_id, user_id, filename, format, status,
                    conversations_added, conversations_updated, messages_added, messages_skipped,
                    chunks_added, duplicate_chunks, bloom_false_positives, secrets_redacted, finished_at)
                VALUES (:id, :projectId, :userId, :filename, :format, :status,
                    :ca, :cu, :ma, :ms, :cha, :dup, :fp, :sec, :finishedAt)""")
                .param("id", r.id())
                .param("projectId", r.projectId())
                .param("userId", userId)
                .param("filename", r.filename())
                .param("format", r.format())
                .param("status", r.status())
                .param("ca", r.conversationsAdded())
                .param("cu", r.conversationsUpdated())
                .param("ma", r.messagesAdded())
                .param("ms", r.messagesSkipped())
                .param("cha", r.chunksAdded())
                .param("dup", r.duplicateChunks())
                .param("fp", r.bloomFalsePositives())
                .param("sec", r.secretsRedacted())
                .param("finishedAt", Db.ts(r.finishedAt()))
                .update();
    }

    public Optional<ImportReport> find(UUID id, UUID projectId, UUID userId) {
        return jdbc.sql("SELECT * FROM imports WHERE id = :id AND project_id = :p AND user_id = :u")
                .param("id", id)
                .param("p", projectId)
                .param("u", userId)
                .query(ROW)
                .optional();
    }

    /** Owner of an import, for background jobs that run outside any request. */
    public Optional<UUID> ownerOf(UUID importId) {
        return jdbc.sql("SELECT user_id FROM imports WHERE id = :id")
                .param("id", importId)
                .query(UUID.class)
                .optional();
    }

    public List<ImportReport> list(UUID projectId, UUID userId) {
        return jdbc.sql("SELECT * FROM imports WHERE project_id = :p AND user_id = :u ORDER BY created_at DESC LIMIT 50")
                .param("p", projectId)
                .param("u", userId)
                .query(ROW)
                .list();
    }

    public void updateCounts(UUID id, String status, int conversationsAdded, int conversationsUpdated,
                             int messagesAdded, int messagesSkipped, int chunksAdded, int duplicateChunks,
                             int bloomFalsePositives, int secretsRedacted, boolean finished) {
        jdbc.sql("""
                UPDATE imports SET status = :status, conversations_added = :ca, conversations_updated = :cu,
                    messages_added = :ma, messages_skipped = :ms, chunks_added = :cha,
                    duplicate_chunks = :dup, bloom_false_positives = :fp, secrets_redacted = :sec,
                    finished_at = CASE WHEN :finished THEN now() ELSE NULL END
                WHERE id = :id""")
                .param("status", status)
                .param("ca", conversationsAdded)
                .param("cu", conversationsUpdated)
                .param("ma", messagesAdded)
                .param("ms", messagesSkipped)
                .param("cha", chunksAdded)
                .param("dup", duplicateChunks)
                .param("fp", bloomFalsePositives)
                .param("sec", secretsRedacted)
                .param("finished", finished)
                .param("id", id)
                .update();
    }

    public void markReady(UUID id, int memoriesExtracted) {
        jdbc.sql("""
                UPDATE imports SET status = 'READY', memories_extracted = :m, finished_at = now()
                WHERE id = :id""")
                .param("m", memoriesExtracted)
                .param("id", id)
                .update();
    }

    public void markFailed(UUID id, String error) {
        jdbc.sql("UPDATE imports SET status = 'FAILED', error = :e, finished_at = now() WHERE id = :id")
                .param("e", error)
                .param("id", id)
                .update();
    }
}
