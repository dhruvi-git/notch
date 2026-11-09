package dev.notch.api.ingest;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ConversationRepository {

    public record ConversationSummary(UUID id, String source, String title, Instant startedAt, int messageCount) {
    }

    private final JdbcClient jdbc;

    public ConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> findId(UUID projectId, String source, String externalId) {
        return jdbc.sql("""
                SELECT id FROM conversations
                WHERE project_id = :projectId AND source = :source AND external_id = :externalId""")
                .param("projectId", projectId)
                .param("source", source)
                .param("externalId", externalId)
                .query(UUID.class)
                .optional();
    }

    public void insert(UUID id, UUID projectId, UUID userId, String source, String externalId,
                       String title, Instant startedAt) {
        jdbc.sql("""
                INSERT INTO conversations (id, project_id, user_id, source, external_id, title, started_at)
                VALUES (:id, :projectId, :userId, :source, :externalId, :title, :startedAt)""")
                .param("id", id)
                .param("projectId", projectId)
                .param("userId", userId)
                .param("source", source)
                .param("externalId", externalId)
                .param("title", title)
                .param("startedAt", Db.ts(startedAt))
                .update();
    }

    public List<ConversationSummary> list(UUID projectId, UUID userId) {
        return jdbc.sql("""
                SELECT c.id, c.source, c.title, c.started_at, COUNT(m.id) AS message_count
                FROM conversations c
                LEFT JOIN messages m ON m.conversation_id = c.id
                WHERE c.project_id = :projectId AND c.user_id = :userId
                GROUP BY c.id
                ORDER BY c.started_at DESC""")
                .param("projectId", projectId)
                .param("userId", userId)
                .query((rs, n) -> new ConversationSummary(Db.uuid(rs, "id"), rs.getString("source"),
                        rs.getString("title"), Db.instant(rs, "started_at"), rs.getInt("message_count")))
                .list();
    }
}
