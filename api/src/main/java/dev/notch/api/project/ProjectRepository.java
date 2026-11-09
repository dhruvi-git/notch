package dev.notch.api.project;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectRepository {

    private static final RowMapper<Project> ROW = (rs, n) -> new Project(
            Db.uuid(rs, "id"), Db.uuid(rs, "user_id"), rs.getString("name"),
            rs.getBoolean("is_private"), Db.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Project p) {
        jdbc.sql("""
                INSERT INTO projects (id, user_id, name, is_private, created_at)
                VALUES (:id, :userId, :name, :isPrivate, :createdAt)""")
                .param("id", p.id())
                .param("userId", p.userId())
                .param("name", p.name())
                .param("isPrivate", p.isPrivate())
                .param("createdAt", Db.ts(p.createdAt()))
                .update();
    }

    public Optional<Project> find(UUID id, UUID userId) {
        return jdbc.sql("SELECT * FROM projects WHERE id = :id AND user_id = :userId")
                .param("id", id)
                .param("userId", userId)
                .query(ROW)
                .optional();
    }

    /** For background work outside a request; never exposed through the API. */
    public Optional<Project> findAnyOwner(UUID id) {
        return jdbc.sql("SELECT * FROM projects WHERE id = :id")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    public List<Project> list(UUID userId) {
        return jdbc.sql("SELECT * FROM projects WHERE user_id = :userId ORDER BY created_at DESC")
                .param("userId", userId)
                .query(ROW)
                .list();
    }
}
