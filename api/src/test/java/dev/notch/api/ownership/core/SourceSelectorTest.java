package dev.notch.api.ownership.core;

import dev.notch.api.ownership.core.SourceSelector.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceSelectorTest {

    @Test
    void skipsDependenciesBuildOutputTestsAndGeneratedCode() {
        List<Entry> tree = List.of(
                new Entry("src/app.py", 4000), new Entry("node_modules/react/index.js", 9000),
                new Entry("dist/bundle.min.js", 9000), new Entry("tests/test_app.py", 3000),
                new Entry("src/app.test.ts", 3000), new Entry("types/api.d.ts", 3000),
                new Entry("db/migrations/0001_init.py", 3000), new Entry("README.md", 3000),
                new Entry("setup.py", 3000), new Entry("web/next.config.js", 3000));
        assertEquals(List.of("src/app.py"), SourceSelector.choose(tree, 20).stream().map(Entry::path).toList());
    }

    @Test
    void spreadsAcrossLanguagesLargestFirst() {
        List<Entry> tree = List.of(
                new Entry("api/Service.java", 9000), new Entry("api/Repo.java", 8000), new Entry("api/Util.java", 1000),
                new Entry("web/page.tsx", 5000), new Entry("web/form.tsx", 4000), new Entry("sql/report.sql", 500));
        assertEquals(List.of("api/Service.java", "web/page.tsx", "sql/report.sql", "api/Repo.java"),
                SourceSelector.choose(tree, 4).stream().map(Entry::path).toList());
    }

    @Test
    void edgeCaseTinyAndHugeFilesAreSkipped() {
        List<Entry> tree = List.of(new Entry("a.py", 20), new Entry("b.py", 2_000_000), new Entry("c.py", 500));
        assertEquals(List.of("c.py"), SourceSelector.choose(tree, 5).stream().map(Entry::path).toList());
    }

    @Test
    void edgeCaseEmptyTreeAndInvalidLimit() {
        assertEquals(List.of(), SourceSelector.choose(List.of(), 5));
        assertThrows(IllegalArgumentException.class, () -> SourceSelector.choose(List.of(), 0));
    }

    @Test
    void respectsTheLimit() {
        List<Entry> many = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) many.add(new Entry("src/f" + i + ".py", 1000 + i));
        List<Entry> chosen = SourceSelector.choose(many, 20);
        assertEquals(20, chosen.size());
        assertEquals("src/f49.py", chosen.get(0).path());
    }
}
