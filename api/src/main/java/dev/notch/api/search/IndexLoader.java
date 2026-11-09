package dev.notch.api.search;

import dev.notch.api.crypto.ContentCipher;
import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.search.core.ProjectIndex;
import dev.notch.api.support.Db;
import dev.notch.engine.select.RandomizedSelect;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a project's {@link ProjectIndex} from the database: decrypts each message once,
 * slices its chunks out of it, and marks chunks that hold an active decision.
 */
@Component
public class IndexLoader {

    private record Msg(UUID id, UUID conversationId, String title, String role, Instant sentAt, String text) {
    }

    private record Span(int start, int end) {
    }

    private final JdbcClient jdbc;
    private final ContentCipher cipher;

    public IndexLoader(JdbcClient jdbc, ContentCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public ProjectIndex load(UUID projectId, UUID userId) {
        String aad = userId.toString();
        Map<UUID, Msg> messages = new HashMap<>();
        jdbc.sql("""
                SELECT m.id, m.conversation_id, c.title, m.role, m.sent_at, m.body
                FROM messages m JOIN conversations c ON c.id = m.conversation_id
                WHERE m.project_id = :p AND m.user_id = :u""")
                .param("p", projectId)
                .param("u", userId)
                .query((rs, n) -> new Msg(Db.uuid(rs, "id"), Db.uuid(rs, "conversation_id"), rs.getString("title"),
                        rs.getString("role"), Db.instant(rs, "sent_at"), cipher.decrypt(rs.getBytes("body"), aad)))
                .list()
                .forEach(m -> messages.put(m.id(), m));

        Map<UUID, List<Span>> decisions = new HashMap<>();
        jdbc.sql("""
                SELECT source_message_id, source_start, source_end FROM memories
                WHERE project_id = :p AND user_id = :u AND kind = 'DECISION' AND status = 'ACTIVE'
                  AND source_message_id IS NOT NULL""")
                .param("p", projectId)
                .param("u", userId)
                .query((rs, n) -> Map.entry(Db.uuid(rs, "source_message_id"),
                        new Span(rs.getInt("source_start"), rs.getInt("source_end"))))
                .list()
                .forEach(e -> decisions.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));

        List<IndexedChunk> chunks = new ArrayList<>();
        jdbc.sql("""
                SELECT id, message_id, start_offset, end_offset, token_count FROM chunks
                WHERE project_id = :p AND user_id = :u""")
                .param("p", projectId)
                .param("u", userId)
                .query((rs, n) -> {
                    Msg m = messages.get(Db.uuid(rs, "message_id"));
                    if (m == null) return null;
                    int start = rs.getInt("start_offset");
                    int end = Math.min(rs.getInt("end_offset"), m.text().length());
                    if (start >= end) return null;
                    boolean decision = overlaps(decisions.get(m.id()), start, end);
                    return new IndexedChunk(Db.uuid(rs, "id"), m.id(), m.conversationId(), m.title(), m.role(),
                            m.sentAt(), m.text().substring(start, end), rs.getInt("token_count"), decision);
                })
                .list()
                .forEach(c -> {
                    if (c != null) chunks.add(c);
                });
        return ProjectIndex.build(chunks, Instant.now(), new RandomizedSelect());
    }

    private static boolean overlaps(List<Span> spans, int start, int end) {
        if (spans == null) return false;
        for (Span s : spans) if (s.start() < end && start < s.end()) return true;
        return false;
    }
}
