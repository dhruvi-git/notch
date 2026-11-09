package dev.notch.api.llm;

import java.util.List;

/** The offline language-model stand-in: heuristic extraction and quote-based answers. */
public final class OfflineLlmClient implements LlmClient {

    private final HeuristicMemoryExtractor extractor = new HeuristicMemoryExtractor();
    private final OfflineAnswerer answerer = new OfflineAnswerer();

    @Override
    public List<ExtractedMemory> extractMemories(ExtractionRequest request) {
        return extractor.extractMemories(request);
    }

    @Override
    public AnswerDraft answer(AnswerRequest request) {
        return answerer.answer(request);
    }

    @Override
    public String name() {
        return "offline";
    }
}
