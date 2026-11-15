package dev.notch.api.health.core;

import dev.notch.explain.CodeMap;
import dev.notch.explain.CodeMapBuilder;
import dev.notch.explain.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkTrailTest {

    @Test
    void editsAreReadFromSessionsAndMatchedToTheMap() {
        String session = "Ran: npm test\nEdited C:\\Users\\me\\worli\\components\\pages\\Portfolio.tsx\nEdited lib/plan.ts\nEdited a file";
        assertEquals(List.of("C:/Users/me/worli/components/pages/Portfolio.tsx", "lib/plan.ts"), WorkTrail.editedPaths(session));
        CodeMap map = new CodeMapBuilder().build(List.of(
                new SourceFile("components/pages/Portfolio.tsx", "export const P = 1;"),
                new SourceFile("pages/Portfolio.tsx", "export const Q = 1;"),
                new SourceFile("lib/plan.ts", "export const plan = 1;")));
        assertEquals("components/pages/Portfolio.tsx", WorkTrail.toMapPath("C:/Users/me/worli/components/pages/Portfolio.tsx", map),
                "the longest matching path wins");
        assertEquals("lib/plan.ts", WorkTrail.toMapPath("lib/plan.ts", map));
        assertNull(WorkTrail.toMapPath("C:/elsewhere/notes.md", map));
    }

    @Test
    void commitsAreReadWithTheirMessage() {
        WorkTrail.Commit c = WorkTrail.commit("Note: Commit 3fba77c9d2: Rebuild as a decision tool Changed: README.md");
        assertEquals(new WorkTrail.Commit("3fba77c", "Rebuild as a decision tool"), c);
        assertNull(WorkTrail.commit("Let's use Postgres."));
    }
}
