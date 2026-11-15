package dev.notch.explain;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipSourceTest {

    private static byte[] zip(String... pathsAndContents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                z.putNextEntry(new ZipEntry(pathsAndContents[i]));
                z.write(pathsAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void readsFilesAndStripsGithubsWrapperFolder() throws Exception {
        ZipSource.Result r = ZipSource.read(zip("notch-main/src/a.ts", "export const a = 1;", "notch-main/README.md", "# hi"));
        assertEquals(List.of("src/a.ts", "README.md"), r.files().stream().map(SourceFile::path).toList());
    }

    @Test
    void dependencyAndBuildFoldersAreNeverUnpacked() throws Exception {
        ZipSource.Result r = ZipSource.read(zip("app/src/a.ts", "export const a = 1;",
                "app/node_modules/react/index.js", "x".repeat(1000), "app/.git/HEAD", "ref", "app/dist/out.js", "y"));
        assertEquals(List.of("src/a.ts"), r.files().stream().map(SourceFile::path).toList());
        assertTrue(CodeMapBuilder.inSkippedFolder("web/node_modules/x/index.js"));
        assertFalse(CodeMapBuilder.inSkippedFolder("src/builder.ts"));
    }

    @Test
    void keepsPathsWhenThereIsNoSingleWrapper() throws Exception {
        ZipSource.Result r = ZipSource.read(zip("src/a.ts", "x", "web/b.ts", "y"));
        assertEquals(List.of("src/a.ts", "web/b.ts"), r.files().stream().map(SourceFile::path).toList());
    }

    @Test
    void skipsBinaryOversizedAndUnsafeEntries() throws Exception {
        String big = "x".repeat(CodeMapBuilder.MAX_FILE_BYTES + 10);
        ZipSource.Result r = ZipSource.read(zip("r/a.ts", "ok", "r/b.png", "PNG\0\0", "r/big.ts", big, "__MACOSX/r/._a.ts", "junk"));
        assertEquals(List.of("a.ts"), r.files().stream().map(SourceFile::path).toList());
        assertEquals(3, r.skipped());
    }

    @Test
    void refusesWhatIsNotAZip() {  // invalid input
        assertDoesNotThrow(() -> assertTrue(ZipSource.read("not a zip".getBytes()).files().isEmpty()));
    }

    @Test
    void mapJsonEscapesAndKeepsEveryField() throws Exception {
        CodeMap m = new CodeMapBuilder().build(List.of(new SourceFile("src/a \"quoted\".ts", "/** Says \"hi\".\n */\nexport const a = 1;")));
        String json = CodeMapJson.write(m);
        assertTrue(json.contains("\"path\":\"src/a \\\"quoted\\\".ts\""), json);
        assertTrue(json.contains("\"role\":\"CODE\""));
        for (String field : List.of("stats", "files", "modules", "areas", "startHere", "entryPoints", "possiblyUnused", "filesWithSecrets")) {
            assertTrue(json.contains("\"" + field + "\":"), field);
        }
    }
}
