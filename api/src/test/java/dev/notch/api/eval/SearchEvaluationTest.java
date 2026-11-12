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
    void hybridIsAtLeastAsGoodAsEitherSignalAlone() throws Exception {
        SearchEvaluation e = SearchEvaluation.fromResources();
        SearchEvaluation.Report r = e.run();
        double hybrid = r.overall().get(SearchPipeline.Mode.HYBRID).mrr();
        double exact = r.overall().get(SearchPipeline.Mode.EXACT_ONLY).mrr();
        double vector = r.overall().get(SearchPipeline.Mode.SEMANTIC_ONLY).mrr();
        String out = System.getProperty("notch.eval.out");
        if (out != null) Files.writeString(Path.of(out), SearchEvaluation.markdown(r, e.questions()), StandardCharsets.UTF_8);
        assertTrue(hybrid >= exact && hybrid >= vector,
                String.format("hybrid %.3f, exact %.3f, vector %.3f", hybrid, exact, vector));
    }
}
