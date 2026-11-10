package dev.notch.api.ideation;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ComparisonRepository {

    public record Stored(UUID id, UUID projectId, String status, byte[] payload, UUID decisionId, String chosen,
                         Instant createdAt, Instant updatedAt) {
    }

    private static final RowMapper<Stored> ROW = (rs, n) -> new Stored(Db.uuid(rs, "id"), Db.uuid(rs, "project_id"),
            rs.getString("status"), rs.getBytes("payload"), Db.uuid(rs, "decision_id"), rs.getString("chosen"),
            Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    public ComparisonRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID projectId, UUID userId, byte[] payload) {
        jdbc.sql("INSERT INTO comparisons (id, project_id, user_id, payload) VALUES (:id, :p, :u, :payload)")
                .param("id", id)
                .param("p", projectId)
                .param("u", userId)
                .param("payload", payload)
                .update();
    }

    public Optional<Stored> find(UUID id, UUID userId) {
        return jdbc.sql("SELECT * FROM comparisons WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", userId)
                .query(ROW)
                .optional();
    }

    public List<Stored> list(UUID projectId, UUID userId) {
        return jdbc.sql("SELECT * FROM comparisons WHERE project_id = :p AND user_id = :u ORDER BY updated_at DESC LIMIT 100")
                .param("p", projectId)
                .param("u", userId)
                .query(ROW)
                .list();
    }

    /** Saves a new version of an open comparison. Returns false if it was decided meanwhile. */
    public boolean updateOpen(UUID id, UUID userId, byte[] payload) {
        return jdbc.sql("""
                UPDATE comparisons SET payload = :payload, updated_at = now()
                WHERE id = :id AND user_id = :u AND status = 'OPEN'""")
                .param("payload", payload)
                .param("id", id)
                .param("u", userId)
                .update() == 1;
    }

    public boolean markDecided(UUID id, UUID userId, UUID decisionId, String chosen) {
        return jdbc.sql("""
                UPDATE comparisons SET status = 'DECIDED', decision_id = :d, chosen = :c, updated_at = now()
                WHERE id = :id AND user_id = :u AND status = 'OPEN'""")
                .param("d", decisionId)
                .param("c", chosen)
                .param("id", id)
                .param("u", userId)
                .update() == 1;
    }

    public boolean delete(UUID id, UUID userId) {
        return jdbc.sql("DELETE FROM comparisons WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", userId)
                .update() == 1;
    }
}
