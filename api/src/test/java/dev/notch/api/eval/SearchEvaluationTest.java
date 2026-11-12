package dev.notch.api.eval;

import dev.notch.api.search.core.SearchPipeline;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the search evaluation as part of the build, and guards the dataset and the headline result. */
class SearchEvaluationTest {

    @Test
    void datasetIsWellFormed() throws Exception {
        SearchEvaluation e = SearchEvaluation.fromResources();
        assertEquals(50, e.questions().size());
        Set<String> ids = new HashSet<>();
        e.messages().forEach(m -> ids.add(m.id()));
        for (SearchEvaluation.Question q : e.questions()) {
            assertFalse(q.relevant().isEmpty(), q.id());
            for (String r : q.relevant()) assertTrue(ids.contains(r), q.id() + " names unknown message " + r);
        }
        assertEquals(50, e.questions().stream().map(SearchEvaluation.Question::id).distinct().count(), "unique ids");
    }

    @Test
    void aRunWithAnotherEmbeddingModelIsComparedSideBySide() throws Exception {
        SearchEvaluation.Report offline = SearchEvaluation.fromResources().run();
        // A stand-in for a real model: a differently sized hashing embedding.
        SearchEvaluation.Report other = SearchEvaluation.fromResources(new dev.notch.api.llm.HashingEmbeddingClient(256)).run();
        String md = SearchEvaluation.withComparison(SearchEvaluation.markdown(offline, SearchEvaluation.fromResources().questions()),
                offline, other, "stand-in-256");
        int section = md.indexOf("## With real embeddings (stand-in-256)");
        assertTrue(section > 0, "the comparison section is added");
        assertTrue(section < md.indexOf("## Every question"), "before the per-question table");
        assertTrue(md.contains("| paraphrase |"));
    }

    @Test
    void hybridIsAtLeastAsGoodAsEitherSignalAlone() throws Exception {
        SearchEvaluation e = SearchEvaluation.fromResources();
        SearchEvaluation.Report r = e.run();
        double hybrid = r.overall().get(SearchPipeline.Mode.HYBRID).mrr();
        double exact = r.overall().get(SearchPipeline.Mode.EXACT_ONLY).mrr();
        double vector = r.overall().get(SearchPipeline.Mode.SEMANTIC_ONLY).mrr();
        String out = System.getProperty("notch.eval.out");
        if (out != null) {
            String md = SearchEvaluation.markdown(r, e.questions());
            // With VOYAGE_API_KEY set, also run with real embeddings and add the comparison.
            dev.notch.api.llm.EmbeddingClient voyage = SearchEvaluation.voyageFromEnvironment();
            if (voyage != null) md = SearchEvaluation.withComparison(md, r, SearchEvaluation.fromResources(voyage).run(), voyage.name());
            Files.writeString(Path.of(out), md, StandardCharsets.UTF_8);
        }
        assertTrue(hybrid >= exact && hybrid >= vector,
                String.format("hybrid %.3f, exact %.3f, vector %.3f", hybrid, exact, vector));
    }
}
