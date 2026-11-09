package dev.notch.api.memory;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class MemoryRepository {

    public record StoredMemory(UUID id, String kind, String status, byte[] payload, UUID supersededBy,
                               UUID sourceMessageId, Integer sourceStart, Integer sourceEnd, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public MemoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID projectId, UUID userId, String kind, byte[] payload,
                       UUID sourceMessageId, int sourceStart, int sourceEnd, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO memories (id, project_id, user_id, kind, payload, source_message_id,
                                      source_start, source_end, created_at)
                VALUES (:id, :p, :u, :kind, :payload, :msg, :s, :e, :createdAt)""")
                .param("id", id)
                .param("p", projectId)
                .param("u", userId)
                .param("kind", kind)
                .param("payload", payload)
                .param("msg", sourceMessageId)
                .param("s", sourceStart)
                .param("e", sourceEnd)
                .param("createdAt", Db.ts(createdAt))
                .update();
    }

    /** Removes memories from an import's messages, so a retried job never duplicates them. */
    public int deleteForImport(UUID importId, UUID userId) {
        return jdbc.sql("""
                DELETE FROM memories
                WHERE user_id = :u AND source_message_id IN (SELECT id FROM messages WHERE import_id = :i)""")
                .param("u", userId)
                .param("i", importId)
                .update();
    }

    /** Memories for a project; {@code kind} and {@code status} are optional filters. */
    public List<StoredMemory> list(UUID projectId, UUID userId, String kind, String status, int limit) {
        return jdbc.sql("""
                SELECT id, kind, status, payload, superseded_by, source_message_id, source_start,
                       source_end, created_at
                FROM memories
                WHERE project_id = :p AND user_id = :u
                  AND (CAST(:kind AS text) IS NULL OR kind = :kind)
                  AND (CAST(:status AS text) IS NULL OR status = :status)
                ORDER BY created_at DESC
                LIMIT :limit""")
                .param("p", projectId)
                .param("u", userId)
                .param("kind", kind)
                .param("status", status)
                .param("limit", limit)
                .query((rs, n) -> new StoredMemory(Db.uuid(rs, "id"), rs.getString("kind"),
                        rs.getString("status"), rs.getBytes("payload"), Db.uuid(rs, "superseded_by"),
                        Db.uuid(rs, "source_message_id"), (Integer) rs.getObject("source_start"),
                        (Integer) rs.getObject("source_end"), Db.instant(rs, "created_at")))
                .list();
    }
}
