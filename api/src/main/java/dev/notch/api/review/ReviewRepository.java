package dev.notch.api.review;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReviewRepository {

    /** Reviews kept per project; older ones are deleted when a new one is added. */
    static final int KEEP = 20;

    public record Stored(UUID id, UUID projectId, String source, String label, String status, String error, int issues,
                         int highIssues, byte[] payload, Instant createdAt, Instant finishedAt) {
    }

    private static final RowMapper<Stored> ROW = (rs, n) -> new Stored(Db.uuid(rs, "id"), Db.uuid(rs, "project_id"),
            rs.getString("source"), rs.getString("label"), rs.getString("status"), rs.getString("error"),
            rs.getInt("issues"), rs.getInt("high_issues"), rs.getBytes("payload"), Db.instant(rs, "created_at"),
            Db.instant(rs, "finished_at"));

    private final JdbcClient jdbc;

    public ReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPending(UUID id, UUID projectId, UUID userId, String source, String label) {
        jdbc.sql("""
                INSERT INTO code_reviews (id, project_id, user_id, source, label, status)
                VALUES (:id, :p, :u, :source, :label, 'PENDING')""")
                .param("id", id).param("p", projectId).param("u", userId).param("source", source).param("label", label)
                .update();
        prune(projectId, userId);
    }

    public void insertDone(UUID id, UUID projectId, UUID userId, String source, String label, int issues, int high, byte[] payload) {
        jdbc.sql("""
                INSERT INTO code_reviews (id, project_id, user_id, source, label, status, issues, high_issues, payload, finished_at)
                VALUES (:id, :p, :u, :source, :label, 'DONE', :issues, :high, :payload, now())""")
                .param("id", id).param("p", projectId).param("u", userId).param("source", source).param("label", label)
                .param("issues", issues).param("high", high).param("payload", payload)
                .update();
        prune(projectId, userId);
    }

    public void finish(UUID id, int issues, int high, byte[] payload) {
        jdbc.sql("""
                UPDATE code_reviews SET status = 'DONE', issues = :issues, high_issues = :high, payload = :payload,
                       finished_at = now(), error = NULL
                WHERE id = :id""")
                .param("id", id).param("issues", issues).param("high", high).param("payload", payload)
                .update();
    }

    public void fail(UUID id, String error) {
        jdbc.sql("UPDATE code_reviews SET status = 'FAILED', error = :error, finished_at = now() WHERE id = :id")
                .param("id", id).param("error", error)
                .update();
    }

    /** Reviews that were running when the server stopped: their code was only in memory, so they can't resume. */
    public int failInterrupted() {
        return jdbc.sql("""
                UPDATE code_reviews SET status = 'FAILED', finished_at = now(),
                       error = 'The server restarted before the review finished. Upload the zip again.'
                WHERE status = 'PENDING'""")
                .update();
    }

    public List<Stored> list(UUID projectId, UUID userId) {
        return jdbc.sql("""
                SELECT id, project_id, source, label, status, error, issues, high_issues, NULL::bytea AS payload,
                       created_at, finished_at
                FROM code_reviews WHERE project_id = :p AND user_id = :u ORDER BY created_at DESC LIMIT :keep""")
                .param("p", projectId).param("u", userId).param("keep", KEEP)
                .query(ROW).list();
    }

    public Optional<Stored> get(UUID id, UUID projectId, UUID userId) {
        return jdbc.sql("SELECT * FROM code_reviews WHERE id = :id AND project_id = :p AND user_id = :u")
                .param("id", id).param("p", projectId).param("u", userId)
                .query(ROW).optional();
    }

    public boolean delete(UUID id, UUID projectId, UUID userId) {
        return jdbc.sql("DELETE FROM code_reviews WHERE id = :id AND project_id = :p AND user_id = :u")
                .param("id", id).param("p", projectId).param("u", userId)
                .update() > 0;
    }

    private void prune(UUID projectId, UUID userId) {
        jdbc.sql("""
                DELETE FROM code_reviews WHERE project_id = :p AND user_id = :u AND id NOT IN (
                    SELECT id FROM code_reviews WHERE project_id = :p AND user_id = :u ORDER BY created_at DESC LIMIT :keep)""")
                .param("p", projectId).param("u", userId).param("keep", KEEP)
                .update();
    }
}
