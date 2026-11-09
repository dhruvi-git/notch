package dev.notch.api.memory;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class MemoryRepository {

    public record StoredMemory(UUID id, UUID projectId, String kind, String status, byte[] payload,
                               UUID supersededBy, UUID sourceMessageId, Integer sourceStart, Integer sourceEnd,
                               UUID conversationId, Instant createdAt) {
    }

    private static final String COLUMNS = """
            mem.id, mem.project_id, mem.kind, mem.status, mem.payload, mem.superseded_by,
            mem.source_message_id, mem.source_start, mem.source_end, msg.conversation_id, mem.created_at
            """;

    private static final org.springframework.jdbc.core.RowMapper<StoredMemory> ROW = (rs, n) -> new StoredMemory(
            Db.uuid(rs, "id"), Db.uuid(rs, "project_id"), rs.getString("kind"), rs.getString("status"),
            rs.getBytes("payload"), Db.uuid(rs, "superseded_by"), Db.uuid(rs, "source_message_id"),
            (Integer) rs.getObject("source_start"), (Integer) rs.getObject("source_end"),
            Db.uuid(rs, "conversation_id"), Db.instant(rs, "created_at"));

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
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM memories mem LEFT JOIN messages msg ON msg.id = mem.source_message_id
                WHERE mem.project_id = :p AND mem.user_id = :u
                  AND (CAST(:kind AS text) IS NULL OR mem.kind = :kind)
                  AND (CAST(:status AS text) IS NULL OR mem.status = :status)
                ORDER BY mem.created_at DESC
                LIMIT :limit""")
                .param("p", projectId)
                .param("u", userId)
                .param("kind", kind)
                .param("status", status)
                .param("limit", limit)
                .query(ROW)
                .list();
    }

    public java.util.Optional<StoredMemory> find(UUID id, UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM memories mem LEFT JOIN messages msg ON msg.id = mem.source_message_id
                WHERE mem.id = :id AND mem.user_id = :u""")
                .param("id", id)
                .param("u", userId)
                .query(ROW)
                .optional();
    }

    /** The memory this one replaced, if any. */
    public java.util.Optional<StoredMemory> predecessor(UUID id, UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM memories mem LEFT JOIN messages msg ON msg.id = mem.source_message_id
                WHERE mem.superseded_by = :id AND mem.user_id = :u
                ORDER BY mem.created_at DESC LIMIT 1""")
                .param("id", id)
                .param("u", userId)
                .query(ROW)
                .optional();
    }

    public void updateStatus(UUID id, UUID userId, String status) {
        jdbc.sql("UPDATE memories SET status = :s WHERE id = :id AND user_id = :u")
                .param("s", status)
                .param("id", id)
                .param("u", userId)
                .update();
    }

    /** Marks {@code original} as replaced by {@code replacement}, only if it is still active. */
    public boolean supersede(UUID original, UUID replacement, UUID userId) {
        return jdbc.sql("""
                UPDATE memories SET status = 'SUPERSEDED', superseded_by = :r
                WHERE id = :id AND user_id = :u AND status = 'ACTIVE'""")
                .param("r", replacement)
                .param("id", original)
                .param("u", userId)
                .update() == 1;
    }

    public void insertManual(UUID id, UUID projectId, UUID userId, String kind, byte[] payload, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO memories (id, project_id, user_id, kind, payload, created_at)
                VALUES (:id, :p, :u, :kind, :payload, :createdAt)""")
                .param("id", id)
                .param("p", projectId)
                .param("u", userId)
                .param("kind", kind)
                .param("payload", payload)
                .param("createdAt", Db.ts(createdAt))
                .update();
    }
}
