package dev.notch.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffParserTest {

    private static final String DIFF = """
            diff --git a/package.json b/package.json
            index 1111111..2222222 100644
            --- a/package.json
            +++ b/package.json
            @@ -12,2 +12,3 @@
               "dependencies": {
            -    "pg": "^8.11.0",
            +    "mongoose": "^8.4.0",
            +    "express": "^4.19.2"
            diff --git a/src/db.js b/src/db.js
            new file mode 100644
            --- /dev/null
            +++ b/src/db.js
            @@ -0,0 +1,2 @@
            +const mongoose = require('mongoose');
            +mongoose.connect(process.env.MONGO_URL);
            diff --git a/old.js b/old.js
            deleted file mode 100644
            --- a/old.js
            +++ /dev/null
            @@ -1 +0,0 @@
            -gone
            """;

    @Test
    void readsAddedLinesWithNewFileLineNumbers() {
        List<FileChange> changes = DiffParser.parse(DIFF);
        assertEquals(2, changes.size());
        FileChange pkg = changes.get(0);
        assertEquals("package.json", pkg.path());
        assertEquals(List.of(13, 14), pkg.added().stream().map(FileChange.Line::number).toList());
        assertEquals("    \"mongoose\": \"^8.4.0\",", pkg.added().get(0).text());
    }

    @Test
    void newFilesStartAtLineOneAndDeletedFilesAreSkipped() {
        List<FileChange> changes = DiffParser.parse(DIFF);
        assertEquals("src/db.js", changes.get(1).path());
        assertEquals(1, changes.get(1).added().get(0).number());
        assertTrue(changes.stream().noneMatch(c -> c.path().equals("old.js")));
    }

    @Test
    void contextLinesAdvanceTheCounterButRemovedLinesDoNot() {
        String d = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -5,4 +5,4 @@\n a\n-b\n+B\n c\n+d\n";
        assertEquals(List.of(6, 8), DiffParser.parse(d).get(0).added().stream().map(FileChange.Line::number).toList());
    }

    @Test
    void emptyOrNonDiffInputGivesNothing() {  // boundary
        assertTrue(DiffParser.parse("").isEmpty());
        assertTrue(DiffParser.parse("not a diff at all").isEmpty());
    }

    @Test
    void contentBecomesAChange() {
        FileChange c = DiffParser.fromContent("a.py", "import os\nimport pymongo", true);
        assertEquals(2, c.added().size());
        assertEquals(2, c.added().get(1).number());
        assertEquals(0, DiffParser.fromContent("a.py", "x", false).added().get(0).number());
    }
}
