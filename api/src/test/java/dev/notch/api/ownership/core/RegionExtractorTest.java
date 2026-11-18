package dev.notch.api.ownership.core;

import dev.notch.api.ownership.core.RegionExtractor.Region;
import dev.notch.api.ownership.core.RegionExtractor.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegionExtractorTest {

    private static final List<Region> REGIONS = RegionExtractor.extract(SampleRepo.FILES, SampleRepo.CATALOG);

    private static Region named(String name) {
        return REGIONS.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no region " + name));
    }

    @Test
    void pythonFunctionsWithLinesSkillsAndConcepts() {
        Region load = named("load_customers");
        assertEquals("model/churn.py", load.path());
        assertEquals(6, load.startLine());
        assertEquals(10, load.endLine());
        assertTrue(load.concepts().contains(Concept.NULL_HANDLING));
        assertTrue(load.skills().containsAll(Set.of(1, 20)), "Python and pandas: " + load.skills());

        Region train = named("train_churn_model");
        assertTrue(train.concepts().contains(Concept.MODEL_TRAINING));
        assertTrue(train.identifiers().contains("train_x") || train.identifiers().contains("features"), train.identifiers().toString());
    }

    @Test
    void typescriptComponentsAndAsyncFunctions() {
        Region dash = named("Dashboard");
        assertTrue(dash.concepts().contains(Concept.STATE_EFFECTS));
        assertTrue(dash.skills().contains(4));
        Region load = named("loadSales");
        assertTrue(load.concepts().containsAll(Set.of(Concept.ASYNC, Concept.HTTP_ENDPOINT, Concept.ERROR_HANDLING)), load.concepts().toString());
    }

    @Test
    void javaMethodsInsideAClassAndRecursion() {
        Region total = named("totalProducts");
        assertTrue(total.concepts().contains(Concept.RECURSION), total.concepts().toString());
        assertEquals("Java", total.language());
        assertTrue(REGIONS.stream().noneMatch(r -> r.name().equals("CategoryTree")), "a long class is split into its methods");
        assertFalse(named("add").concepts().contains(Concept.RECURSION));
    }

    @Test
    void sqlStatementsWithJoinsAndAggregation() {
        Region report = REGIONS.stream().filter(r -> r.path().equals("sql/report.sql")).findFirst().orElseThrow();
        assertTrue(report.concepts().containsAll(Set.of(Concept.SQL_JOIN, Concept.AGGREGATION, Concept.SORTING)));
        assertTrue(report.skills().contains(17));
        assertEquals(1, REGIONS.stream().filter(r -> r.path().equals("sql/report.sql")).count(), "SELECT 1 is too short to ask about");
    }

    @Test
    void edgeCasesNonCodeTinyAndUnstructuredFiles() {
        assertTrue(REGIONS.stream().noneMatch(r -> r.path().equals("README.md")));
        assertTrue(REGIONS.stream().noneMatch(r -> r.name().equals("x")), "a two-line function is too small");
        // A script with no definitions is still read, in windows.
        String script = "import os\n".repeat(3) + "print(os.getcwd())\n".repeat(40);
        List<Region> w = RegionExtractor.extract(List.of(new SourceFile("run.py", script)), SampleRepo.CATALOG);
        assertEquals(2, w.size());
        assertEquals(1, w.get(0).startLine());
        assertEquals(RegionExtractor.WINDOW, w.get(0).endLine());
        assertEquals(List.of(), RegionExtractor.extract(List.of(), SampleRepo.CATALOG));
    }

    @Test
    void librariesAreCreditedThroughTheNamesAFileImports() {
        var py = RegionExtractor.importedNames("import pandas as pd\nfrom sklearn.linear_model import LogisticRegression, Ridge\nimport os\n", SampleRepo.CATALOG);
        assertEquals(20, py.get("pd"));
        assertEquals(42, py.get("LogisticRegression"));
        assertEquals(42, py.get("Ridge"));
        assertNull(py.get("os"));
        var js = RegionExtractor.importedNames("import React, { useEffect as useFx } from \"react\";\n", SampleRepo.CATALOG);
        assertEquals(60, js.get("useEffect"));
        assertEquals(60, js.get("React"));
        // Using an imported name credits the region: Dashboard uses useEffect, so it shows React.
        assertTrue(named("Dashboard").skills().contains(60));
        assertTrue(named("train_churn_model").skills().contains(42));
    }

    @Test
    void identifiersSkipKeywordsAndStrings() {
        List<String> ids = RegionExtractor.identifiers("def total(items):\n    return sum(item.price for item in items) # \"secret_word\"");
        assertTrue(ids.contains("total") && ids.contains("price"), ids.toString());
        assertFalse(ids.contains("return") || ids.contains("items"));
        assertFalse(RegionExtractor.identifiers("x = \"hidden_name\"").contains("hidden_name"));
    }
}
