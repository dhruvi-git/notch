package dev.notch.api.search;

import dev.notch.api.config.ApiException;
import dev.notch.api.ingest.ChunkRepository;
import dev.notch.api.llm.EmbeddingClient;
import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectService;
import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.search.core.ProjectIndex;
import dev.notch.api.search.core.QueryTerms;
import dev.notch.engine.rank.HybridRanker;
import dev.notch.engine.select.RandomizedSelect;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hybrid search over one project:
 * <ol>
 *   <li>Exact: the suffix array scores chunks containing the query's terms and phrases.</li>
 *   <li>Semantic: pgvector returns the chunks nearest to the query's embedding (skipped for
 *       private projects).</li>
 *   <li>The two candidate sets are merged and ranked by {@link HybridRanker}, which also
 *       weighs recency and whether a chunk holds a decision.</li>
 * </ol>
 * Each hit carries its per-signal scores, so the UI can show why it matched.
 */
@Service
public class SearchService {

    static final int MAX_QUERY_CHARS = 500;
    static final int EXACT_CANDIDATES = 200;
    static final int SEMANTIC_CANDIDATES = 50;
    static final Duration RECENCY_HALF_LIFE = Duration.ofDays(14);
    /** When the query is about decisions, the decision signal counts for more. */
    static final HybridRanker.Weights DECISION_WEIGHTS = new HybridRanker.Weights(0.30, 0.30, 0.10, 0.30);

    /** One result: the chunk, its ranking with per-signal contributions, and highlight spans. */
    public record Hit(IndexedChunk chunk, HybridRanker.Ranked ranked, List<int[]> highlights, UUID threadId) {
    }

    public record Outcome(Project project, QueryTerms terms, Instant since, List<Hit> hits,
                          int exactCandidates, int semanticCandidates, long indexVersion, long tookMillis) {
    }

    private final ProjectService projects;
    private final IndexRegistry indexes;
    private final ChunkRepository chunks;
    private final EmbeddingClient embeddings;
    private final RandomizedSelect select = new RandomizedSelect();

    public SearchService(ProjectService projects, IndexRegistry indexes, ChunkRepository chunks,
                         EmbeddingClient embeddings) {
        this.projects = projects;
        this.indexes = indexes;
        this.chunks = chunks;
        this.embeddings = embeddings;
    }

    public Outcome search(UUID projectId, String query, int k, Instant sinceOverride) {
        long started = System.nanoTime();
        if (query == null || query.isBlank()) throw ApiException.badRequest("empty_query", "Type something to search for.");
        if (query.length() > MAX_QUERY_CHARS) {
            throw ApiException.badRequest("query_too_long", "Queries can be up to " + MAX_QUERY_CHARS + " characters.");
        }
        if (k < 1 || k > 50) throw ApiException.badRequest("invalid_k", "k must be between 1 and 50.");

        Project project = projects.require(projectId);
        Instant now = Instant.now();
        QueryTerms q = QueryTerms.parse(query, now);
        Instant since = sinceOverride != null ? sinceOverride : q.since();
        ProjectIndex idx = indexes.get(project);
        if (idx.isEmpty()) {
            return new Outcome(project, q, since, List.of(), 0, 0, indexes.version(projectId), elapsed(started));
        }

        // Exact candidates, capped to the best few hundred with randomized selection.
        Map<UUID, Integer> exact = idx.exactScores(q);
        List<UUID> exactIds = select.topK(new ArrayList<>(exact.keySet()), EXACT_CANDIDATES,
                Comparator.comparingInt(exact::get));

        Map<UUID, Double> semantic = project.isPrivate() ? Map.of() : semantic(project, query);

        Set<UUID> ids = new LinkedHashSet<>(exactIds);
        ids.addAll(semantic.keySet());
        if (q.isEmpty() && q.decisionIntent()) {
            // "What did we decide last week?" has no topic words: every decision is a candidate.
            for (IndexedChunk c : idx.decisionChunks(since)) ids.add(c.chunkId());
        }

        List<HybridRanker.Candidate> candidates = new ArrayList<>();
        for (UUID id : ids) {
            IndexedChunk c = idx.chunk(id);
            if (c == null) continue;  // embedded after the index was built; picked up on rebuild
            if (since != null && c.sentAt().isBefore(since)) continue;
            candidates.add(new HybridRanker.Candidate(id.toString(), exact.getOrDefault(id, 0),
                    semantic.getOrDefault(id, 0.0), c.sentAt(), c.decision(), c.tokens()));
        }

        HybridRanker.Weights weights = q.decisionIntent() ? DECISION_WEIGHTS : HybridRanker.Weights.defaults();
        HybridRanker ranker = new HybridRanker(weights, RECENCY_HALF_LIFE, select);
        List<Hit> hits = new ArrayList<>();
        for (HybridRanker.Ranked r : ranker.rank(candidates, now, k)) {
            IndexedChunk c = idx.chunk(UUID.fromString(r.candidate().chunkId()));
            hits.add(new Hit(c, r, ProjectIndex.highlights(c.text(), q), idx.threadOf(c.conversationId())));
        }
        return new Outcome(project, q, since, hits, exactIds.size(), semantic.size(),
                indexes.version(projectId), elapsed(started));
    }

    private Map<UUID, Double> semantic(Project project, String query) {
        float[] v = embeddings.embed(List.of(query)).get(0);
        if (v == null) return Map.of();
        return chunks.nearest(project.id(), project.userId(), v, SEMANTIC_CANDIDATES);
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
