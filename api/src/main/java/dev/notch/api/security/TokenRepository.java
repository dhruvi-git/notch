package dev.notch.api.security;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Session tokens, personal tokens and one-time sign-in codes, all stored as SHA-256 hashes. */
@Repository
public class TokenRepository {

    public record ActiveToken(UUID id, UUID userId) {
    }

    public record TokenInfo(UUID id, String name, String prefix, Instant createdAt, Instant lastUsedAt) {
    }

    private final JdbcClient jdbc;

    public TokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID userId, String name, byte[] hash, String prefix, String kind, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO api_tokens (id, user_id, name, token_hash, token_prefix, kind, expires_at)
                VALUES (:id, :u, :name, :hash, :prefix, :kind, :exp)""")
                .param("id", id)
                .param("u", userId)
                .param("name", name)
                .param("hash", hash)
                .param("prefix", prefix)
                .param("kind", kind)
                .param("exp", Db.ts(expiresAt))
                .update();
    }

    public Optional<ActiveToken> findActive(byte[] hash) {
        return jdbc.sql("""
                SELECT id, user_id FROM api_tokens
                WHERE token_hash = :h AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())""")
                .param("h", hash)
                .query((rs, n) -> new ActiveToken(Db.uuid(rs, "id"), Db.uuid(rs, "user_id")))
                .optional();
    }

    /** Records use at most every five minutes, to avoid a write on every request. */
    public void touch(UUID id) {
        jdbc.sql("""
                UPDATE api_tokens SET last_used_at = now()
                WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '5 minutes')""")
                .param("id", id)
                .update();
    }

    public void revokeByHash(byte[] hash) {
        jdbc.sql("UPDATE api_tokens SET revoked_at = now() WHERE token_hash = :h AND revoked_at IS NULL")
                .param("h", hash)
                .update();
    }

    public boolean revokePersonal(UUID id, UUID userId) {
        return jdbc.sql("""
                UPDATE api_tokens SET revoked_at = now()
                WHERE id = :id AND user_id = :u AND kind = 'personal' AND revoked_at IS NULL""")
                .param("id", id)
                .param("u", userId)
                .update() == 1;
    }

    public List<TokenInfo> listPersonal(UUID userId) {
        return jdbc.sql("""
                SELECT id, name, token_prefix, created_at, last_used_at FROM api_tokens
                WHERE user_id = :u AND kind = 'personal' AND revoked_at IS NULL
                ORDER BY created_at DESC""")
                .param("u", userId)
                .query((rs, n) -> new TokenInfo(Db.uuid(rs, "id"), rs.getString("name"), rs.getString("token_prefix"),
                        Db.instant(rs, "created_at"), Db.instant(rs, "last_used_at")))
                .list();
    }

    public int countPersonal(UUID userId) {
        return jdbc.sql("SELECT COUNT(*) FROM api_tokens WHERE user_id = :u AND kind = 'personal' AND revoked_at IS NULL")
                .param("u", userId)
                .query(Integer.class)
                .single();
    }

    public void insertAuthCode(byte[] hash, UUID userId, Instant expiresAt) {
        jdbc.sql("INSERT INTO auth_codes (code_hash, user_id, expires_at) VALUES (:h, :u, :exp)")
                .param("h", hash)
                .param("u", userId)
                .param("exp", Db.ts(expiresAt))
                .update();
    }

    /** Uses up a sign-in code. Atomic, so a code can never be traded twice. */
    public Optional<UUID> consumeAuthCode(byte[] hash) {
        return jdbc.sql("""
                UPDATE auth_codes SET used_at = now()
                WHERE code_hash = :h AND used_at IS NULL AND expires_at > now()
                RETURNING user_id""")
                .param("h", hash)
                .query(UUID.class)
                .optional();
    }
}
