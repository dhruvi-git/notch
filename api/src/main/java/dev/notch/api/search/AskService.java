package dev.notch.api.search;

import dev.notch.api.config.ApiException;
import dev.notch.api.llm.AnswerDraft;
import dev.notch.api.llm.AnswerRequest;
import dev.notch.api.llm.LlmClient;
import dev.notch.api.llm.LlmConfig;
import dev.notch.api.security.CurrentUser;
import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.usage.UsageService;
import dev.notch.engine.rank.HybridRanker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers a question from the project's own history, with citations:
 * search → keep the best chunks that fit the token budget → ask the model to answer only from
 * them → drop any citation that does not point at a supplied chunk.
 */
@Service
public class AskService {

    static final int SEARCH_DEPTH = 20;
    static final int CONTEXT_TOKEN_BUDGET = 3000;

    public record CitedSource(int n, IndexedChunk chunk, int start, int end) {
    }

    public record Answer(String text, boolean grounded, List<CitedSource> citations, String model,
                         int contextChunks, int remainingQuestions) {
    }

    private final SearchService search;
    private final UsageService usage;
    private final CurrentUser currentUser;
    private final LlmClient llm;
    private final LlmClient offline;

    public AskService(SearchService search, UsageService usage, CurrentUser currentUser, LlmClient llm,
                      @Qualifier(LlmConfig.OFFLINE) LlmClient offline) {
        this.search = search;
        this.usage = usage;
        this.currentUser = currentUser;
        this.llm = llm;
        this.offline = offline;
    }

    public Answer ask(UUID projectId, String question) {
        SearchService.Outcome found = search.search(projectId, question, SEARCH_DEPTH, null);
        LlmClient model = found.project().isPrivate() ? offline : llm;
        UUID userId = currentUser.id();
        if (found.hits().isEmpty()) {
            // Nothing to send to a model, so this does not use up a question.
            return new Answer(emptyMessage(found), false, List.of(), model.name(), 0,
                    usage.remaining(userId).get(UsageService.QUESTION));
        }
        usage.consume(userId, UsageService.QUESTION);

        List<HybridRanker.Ranked> ranked = found.hits().stream().map(SearchService.Hit::ranked).toList();
        List<HybridRanker.Ranked> fitted = HybridRanker.fitToBudget(ranked, CONTEXT_TOKEN_BUDGET);
        Map<UUID, IndexedChunk> byId = new HashMap<>();
        for (SearchService.Hit h : found.hits()) byId.put(h.chunk().chunkId(), h.chunk());

        List<AnswerRequest.Context> contexts = new ArrayList<>();
        for (HybridRanker.Ranked r : fitted) {
            IndexedChunk c = byId.get(UUID.fromString(r.candidate().chunkId()));
            contexts.add(new AnswerRequest.Context(contexts.size() + 1, c.chunkId(), c.conversationTitle(),
                    c.sentAt(), c.text(), c.decision()));
        }

        AnswerDraft draft;
        try {
            draft = model.answer(new AnswerRequest(question, found.terms(), contexts));
        } catch (RuntimeException e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "model_unavailable",
                    "The answer service is unavailable right now. Search still works.");
        }

        List<CitedSource> cited = new ArrayList<>();
        for (AnswerDraft.Citation c : draft.citations()) {
            IndexedChunk chunk = byId.get(c.chunkId());
            boolean supplied = contexts.stream().anyMatch(x -> x.chunkId().equals(c.chunkId()));
            if (chunk == null || !supplied) continue;  // never show a citation we did not provide
            int start = Math.max(0, Math.min(c.start(), chunk.text().length()));
            int end = Math.max(start, Math.min(c.end(), chunk.text().length()));
            cited.add(new CitedSource(c.n(), chunk, start, end));
        }
        boolean grounded = draft.grounded() && !cited.isEmpty();
        return new Answer(draft.text(), grounded, cited, model.name(), contexts.size(),
                usage.remaining(userId).get(UsageService.QUESTION));
    }

    private static String emptyMessage(SearchService.Outcome found) {
        return found.exactCandidates() == 0 && found.semanticCandidates() == 0 && found.hits().isEmpty()
                ? "Nothing in this project matches that yet. Import the conversation where it was discussed, "
                        + "or try other words."
                : "Nothing matched in that time range.";
    }
}
