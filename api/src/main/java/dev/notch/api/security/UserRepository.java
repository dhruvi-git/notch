package dev.notch.api.security;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    public record User(UUID id, String handle, String avatarUrl, boolean github) {
    }

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the user on first sign-in; afterwards keeps handle and avatar in sync with GitHub. */
    public UUID upsertGithub(long githubId, String handle, String avatarUrl) {
        return jdbc.sql("""
                INSERT INTO users (id, github_id, handle, avatar_url) VALUES (:id, :gh, :handle, :avatar)
                ON CONFLICT (github_id) DO UPDATE SET handle = EXCLUDED.handle, avatar_url = EXCLUDED.avatar_url
                RETURNING id""")
                .param("id", UUID.randomUUID())
                .param("gh", githubId)
                .param("handle", handle)
                .param("avatar", avatarUrl)
                .query(UUID.class)
                .single();
    }

    /** Local development only: a test user by handle, created if needed. */
    public UUID findOrCreateDev(String handle) {
        Optional<UUID> existing = jdbc.sql("SELECT id FROM users WHERE github_id IS NULL AND handle = :h ORDER BY created_at LIMIT 1")
                .param("h", handle)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) return existing.get();
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, handle) VALUES (:id, :h)").param("id", id).param("h", handle).update();
        return id;
    }

    public Optional<User> find(UUID id) {
        return jdbc.sql("SELECT id, handle, avatar_url, github_id FROM users WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new User(Db.uuid(rs, "id"), rs.getString("handle"), rs.getString("avatar_url"),
                        rs.getObject("github_id") != null))
                .optional();
    }
}
