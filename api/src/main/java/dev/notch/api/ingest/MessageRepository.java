package dev.notch.api.ingest;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class MessageRepository {

    /** A stored message with its encrypted body. */
    public record StoredMessage(UUID id, UUID conversationId, String conversationTitle, int seq,
                                String role, byte[] body, Instant sentAt) {
    }

    private final JdbcClient jdbc;

    public MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The next message from the other speaker in the same conversation, such as the answer to a
     * question. Empty if the message is the last word.
     */
    public java.util.Optional<StoredMessage> nextReply(UUID messageId, UUID userId) {
        return jdbc.sql("""
                SELECT n.id, n.conversation_id, c.title, n.seq, n.role, n.body, n.sent_at
                FROM messages m
                JOIN messages n ON n.conversation_id = m.conversation_id AND n.seq > m.seq AND n.role <> m.role
                JOIN conversations c ON c.id = n.conversation_id
                WHERE m.id = :id AND m.user_id = :u
                ORDER BY n.seq
                LIMIT 1""")
                .param("id", messageId)
                .param("u", userId)
                .query((rs, n) -> new StoredMessage(dev.notch.api.support.Db.uuid(rs, "id"),
                        dev.notch.api.support.Db.uuid(rs, "conversation_id"), rs.getString("title"), rs.getInt("seq"),
                        rs.getString("role"), rs.getBytes("body"), dev.notch.api.support.Db.instant(rs, "sent_at")))
                .optional();
    }

    /** How many messages the person wrote in a conversation, across every capture of it. */
    public int userMessages(UUID conversationId) {
        return jdbc.sql("SELECT count(*) FROM messages WHERE conversation_id = :c AND role = 'user'")
                .param("c", conversationId)
                .query(Integer.class)
                .single();
    }

    public Set<String> externalIds(UUID conversationId) {
        return new HashSet<>(jdbc.sql("SELECT external_id FROM messages WHERE conversation_id = :c")
                .param("c", conversationId)
                .query(String.class)
                .list());
    }

    public int nextSeq(UUID conversationId) {
        return jdbc.sql("SELECT COALESCE(MAX(seq), -1) + 1 FROM messages WHERE conversation_id = :c")
                .param("c", conversationId)
                .query(Integer.class)
                .single();
    }

    public void insert(UUID id, UUID conversationId, UUID projectId, UUID userId, UUID importId,
                       String externalId, int seq, String role, byte[] body, int charLength, Instant sentAt) {
        jdbc.sql("""
                INSERT INTO messages (id, conversation_id, project_id, user_id, import_id, external_id,
                                      seq, role, body, char_length, sent_at)
                VALUES (:id, :conversationId, :projectId, :userId, :importId, :externalId,
                        :seq, :role, :body, :charLength, :sentAt)""")
                .param("id", id)
                .param("conversationId", conversationId)
                .param("projectId", projectId)
                .param("userId", userId)
                .param("importId", importId)
                .param("externalId", externalId)
                .param("seq", seq)
                .param("role", role)
                .param("body", body)
                .param("charLength", charLength)
                .param("sentAt", Db.ts(sentAt))
                .update();
    }

    /** Messages added by one import, grouped by conversation and in order. */
    public List<StoredMessage> forImport(UUID importId, UUID userId) {
        return jdbc.sql("""
                SELECT m.id, m.conversation_id, c.title, m.seq, m.role, m.body, m.sent_at
                FROM messages m JOIN conversations c ON c.id = m.conversation_id
                WHERE m.import_id = :importId AND m.user_id = :userId
                ORDER BY m.conversation_id, m.seq""")
                .param("importId", importId)
                .param("userId", userId)
                .query((rs, n) -> new StoredMessage(Db.uuid(rs, "id"), Db.uuid(rs, "conversation_id"),
                        rs.getString("title"), rs.getInt("seq"), rs.getString("role"),
                        rs.getBytes("body"), Db.instant(rs, "sent_at")))
                .list();
    }
}
