package dev.notch.api.explain;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CodeMapRepository {

    /** Maps kept per project; older ones are deleted on upload. */
    static final int KEEP = 5;

    public record Stored(UUID id, UUID projectId, String source, String label, int files, byte[] payload, Instant createdAt) {
    }

    private static final RowMapper<Stored> ROW = (rs, n) -> new Stored(Db.uuid(rs, "id"), Db.uuid(rs, "project_id"),
            rs.getString("source"), rs.getString("label"), rs.getInt("files"), rs.getBytes("payload"), Db.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public CodeMapRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID projectId, UUID userId, String source, String label, int files, byte[] payload) {
        jdbc.sql("""
                INSERT INTO code_maps (id, project_id, user_id, source, label, files, payload)
                VALUES (:id, :p, :u, :source, :label, :files, :payload)""")
                .param("id", id).param("p", projectId).param("u", userId).param("source", source)
                .param("label", label).param("files", files).param("payload", payload)
                .update();
        jdbc.sql("""
                DELETE FROM code_maps WHERE project_id = :p AND user_id = :u AND id NOT IN (
                    SELECT id FROM code_maps WHERE project_id = :p AND user_id = :u ORDER BY created_at DESC LIMIT :keep)""")
                .param("p", projectId).param("u", userId).param("keep", KEEP)
                .update();
    }

    public Optional<Stored> latest(UUID projectId, UUID userId) {
        return jdbc.sql("SELECT * FROM code_maps WHERE project_id = :p AND user_id = :u ORDER BY created_at DESC LIMIT 1")
                .param("p", projectId).param("u", userId)
                .query(ROW).optional();
    }
}
