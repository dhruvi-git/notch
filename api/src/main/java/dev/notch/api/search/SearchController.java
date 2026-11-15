package dev.notch.api.search;

import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectService;
import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.search.core.ProjectIndex;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class SearchController {

    public record SourceRef(UUID chunkId, UUID messageId, UUID conversationId, String conversationTitle,
                            String role, Instant sentAt) {
        static SourceRef of(IndexedChunk c) {
            return new SourceRef(c.chunkId(), c.messageId(), c.conversationId(), c.conversationTitle(),
                    c.role(), c.sentAt());
        }
    }

    public record SearchHit(SourceRef source, String text, List<int[]> highlights, double score,
                            Map<String, Double> why, boolean decision, UUID threadId) {
    }

    public record SearchResponse(String query, List<String> terms, List<String> phrases, List<String> expansions,
                                 boolean decisionIntent,
                                 Instant since, List<SearchHit> hits, int exactCandidates,
                                 int semanticCandidates, long indexVersion, long tookMillis) {
    }

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    public record Citation(int n, SourceRef source, String text, int start, int end, String quote) {
    }

    public record AskResponse(String answer, boolean grounded, List<Citation> citations, String model,
                              int contextChunks, int remainingQuestions) {
    }

    public record ThreadResponse(UUID threadId, List<Map<String, Object>> conversations) {
    }

    private final SearchService search;
    private final AskService ask;
    private final IndexRegistry indexes;
    private final ProjectService projects;

    private final dev.notch.api.explain.CodeMapService codeMaps;

    public SearchController(SearchService search, AskService ask, IndexRegistry indexes, ProjectService projects,
                            dev.notch.api.explain.CodeMapService codeMaps) {
        this.codeMaps = codeMaps;
        this.search = search;
        this.ask = ask;
        this.indexes = indexes;
        this.projects = projects;
    }

    /** Hybrid search. {@code since} (ISO-8601) overrides phrases like "last week" in the query. */
    @GetMapping("/search")
    public SearchResponse search(@PathVariable UUID projectId, @RequestParam("q") String q,
                                 @RequestParam(defaultValue = "10") int k,
                                 @RequestParam(required = false) Instant since) {
        SearchService.Outcome o = search.search(projectId, q, k, since);
        List<SearchHit> hits = new ArrayList<>();
        for (SearchService.Hit h : o.hits()) {
            hits.add(new SearchHit(SourceRef.of(h.chunk()), h.chunk().text(), h.highlights(), h.ranked().score(),
                    h.ranked().contributions(), h.chunk().decision(), h.threadId()));
        }
        return new SearchResponse(q, o.terms().terms(), o.terms().phrases(), o.terms().expansions(),
                o.terms().decisionIntent(), o.since(),
                hits, o.exactCandidates(), o.semanticCandidates(), o.indexVersion(), o.tookMillis());
    }

    /** A cited answer from the project's history. Uses one of the daily questions. */
    @PostMapping("/ask")
    public AskResponse ask(@PathVariable UUID projectId, @Valid @RequestBody AskRequest req) {
        AskService.Answer a = ask.ask(projectId, req.question());
        List<Citation> citations = new ArrayList<>();
        for (AskService.CitedSource c : a.citations()) {
            String text = c.chunk().text();
            citations.add(new Citation(c.n(), SourceRef.of(c.chunk()), text, c.start(), c.end(),
                    text.substring(c.start(), c.end())));
        }
        return new AskResponse(a.text(), a.grounded(), citations, a.model(), a.contextChunks(), a.remainingQuestions());
    }

    /** {@code @} suggestions: file paths, identifiers, error names and conversation titles. */
    @GetMapping("/autocomplete")
    public List<ProjectIndex.Suggestion> autocomplete(@PathVariable UUID projectId,
                                                      @RequestParam(defaultValue = "") String prefix,
                                                      @RequestParam(defaultValue = "8") int k) {
        Project p = projects.require(projectId);
        int limit = Math.max(1, Math.min(k, 20));
        // Files from the code map first (a radix tree over paths), then names from conversations.
        List<ProjectIndex.Suggestion> out = new ArrayList<>(codeMaps.fileSuggestions(p, prefix, (limit + 1) / 2));
        java.util.Set<String> seen = new java.util.HashSet<>();
        out.forEach(s -> seen.add(s.key().toLowerCase(java.util.Locale.ROOT)));
        for (ProjectIndex.Suggestion s : indexes.get(p).autocomplete(prefix, limit)) {
            if (out.size() >= limit) break;
            if (seen.add(s.key().toLowerCase(java.util.Locale.ROOT))) out.add(s);
        }
        return out;
    }

    /** Conversations linked into threads because they share files or errors. */
    @GetMapping("/threads")
    public List<ThreadResponse> threads(@PathVariable UUID projectId) {
        Project p = projects.require(projectId);
        ProjectIndex idx = indexes.get(p);
        List<ThreadResponse> out = new ArrayList<>();
        for (List<UUID> group : idx.linkedThreads()) {
            List<Map<String, Object>> convs = new ArrayList<>();
            for (UUID id : group) convs.add(Map.of("id", id, "title", String.valueOf(idx.conversationTitle(id))));
            out.add(new ThreadResponse(group.get(0), convs));
        }
        return out;
    }

    /** Index size and build time, for the UI and the Lab page. */
    @GetMapping("/index")
    public Map<String, Object> index(@PathVariable UUID projectId) {
        Project p = projects.require(projectId);
        ProjectIndex.Stats s = indexes.get(p).stats();
        return Map.of("version", indexes.version(projectId), "stats", s);
    }
}
