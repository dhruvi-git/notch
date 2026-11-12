package dev.notch.api.ingest;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ChunkRepository {

    public record PendingChunk(UUID id, UUID messageId, int start, int end) {
    }

    private final JdbcClient jdbc;

    public ChunkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The exact (authoritative) duplicate check behind the Bloom filter. */
    public boolean exists(UUID projectId, byte[] contentHash) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chunks WHERE project_id = :p AND content_hash = :h)")
                .param("p", projectId)
                .param("h", contentHash)
                .query(Boolean.class)
                .single();
    }

    /** Inserts unless the same content is already stored. Returns true if a row was added. */
    public boolean insert(UUID id, UUID projectId, UUID userId, UUID messageId, int start, int end,
                          byte[] contentHash, int tokenCount) {
        return jdbc.sql("""
                INSERT INTO chunks (id, project_id, user_id, message_id, start_offset, end_offset,
                                    content_hash, token_count)
                VALUES (:id, :projectId, :userId, :messageId, :start, :end, :hash, :tokens)
                ON CONFLICT (project_id, content_hash) DO NOTHING""")
                .param("id", id)
                .param("projectId", projectId)
                .param("userId", userId)
                .param("messageId", messageId)
                .param("start", start)
                .param("end", end)
                .param("hash", contentHash)
                .param("tokens", tokenCount)
                .update() == 1;
    }

    public List<byte[]> hashes(UUID projectId) {
        return jdbc.sql("SELECT content_hash FROM chunks WHERE project_id = :p")
                .param("p", projectId)
                .query((rs, n) -> rs.getBytes(1))
                .list();
    }

    public List<PendingChunk> withoutEmbedding(UUID importId, UUID userId) {
        return jdbc.sql("""
                SELECT ch.id, ch.message_id, ch.start_offset, ch.end_offset
                FROM chunks ch JOIN messages m ON m.id = ch.message_id
                WHERE m.import_id = :importId AND ch.user_id = :userId AND ch.embedding IS NULL""")
                .param("importId", importId)
                .param("userId", userId)
                .query((rs, n) -> new PendingChunk(Db.uuid(rs, "id"), Db.uuid(rs, "message_id"),
                        rs.getInt("start_offset"), rs.getInt("end_offset")))
                .list();
    }

    /**
     * The {@code limit} chunks nearest to {@code vector} by cosine distance (pgvector's HNSW
     * index), with similarity = 1 - distance.
     */
    public java.util.Map<UUID, Double> nearest(UUID projectId, UUID userId, float[] vector, String model, int limit) {
        java.util.Map<UUID, Double> out = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, 1 - (embedding <=> CAST(:v AS vector)) AS similarity
                FROM chunks
                WHERE project_id = :p AND user_id = :u AND embedding IS NOT NULL AND embedding_model = :m
                ORDER BY embedding <=> CAST(:v AS vector)
                LIMIT :limit""")
                .param("v", Db.vector(vector))
                .param("p", projectId)
                .param("u", userId)
                .param("m", model)
                .param("limit", limit)
                .query((rs, n) -> java.util.Map.entry(Db.uuid(rs, "id"), rs.getDouble("similarity")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Stores a chunk's vector and the model that made it; a null vector marks it done with nothing to embed. */
    public void setEmbedding(UUID chunkId, float[] vector, String model) {
        jdbc.sql("UPDATE chunks SET embedding = CAST(:v AS vector), embedding_model = :m WHERE id = :id")
                .param("v", vector == null ? null : Db.vector(vector))
                .param("m", model)
                .param("id", chunkId)
                .update();
    }

    /** A chunk whose vector is missing or from another model, with its message's encrypted body. */
    public record StaleChunk(UUID id, UUID userId, int start, int end, byte[] body) {
    }

    /** Up to {@code limit} chunks of a non-private project not yet embedded with {@code model}. */
    public List<StaleChunk> staleEmbeddings(UUID projectId, String model, int limit) {
        return jdbc.sql("""
                SELECT ch.id, ch.user_id, ch.start_offset, ch.end_offset, m.body
                FROM chunks ch
                JOIN messages m ON m.id = ch.message_id
                JOIN projects p ON p.id = ch.project_id
                WHERE ch.project_id = :p AND NOT p.is_private AND ch.embedding_model IS DISTINCT FROM :m
                LIMIT :limit""")
                .param("p", projectId)
                .param("m", model)
                .param("limit", limit)
                .query((rs, n) -> new StaleChunk(Db.uuid(rs, "id"), Db.uuid(rs, "user_id"), rs.getInt("start_offset"),
                        rs.getInt("end_offset"), rs.getBytes("body")))
                .list();
    }

    /** Non-private projects with chunks not yet embedded with {@code model}. */
    public List<UUID> projectsWithStaleEmbeddings(String model) {
        return jdbc.sql("""
                SELECT DISTINCT ch.project_id FROM chunks ch JOIN projects p ON p.id = ch.project_id
                WHERE NOT p.is_private AND ch.embedding_model IS DISTINCT FROM :m""")
                .param("m", model)
                .query((rs, n) -> Db.uuid(rs, "project_id"))
                .list();
    }
}
