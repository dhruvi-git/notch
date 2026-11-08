package dev.notch.engine.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorpusIndexTest {

    private static CorpusIndex index(String... texts) {
        List<CorpusIndex.Doc> docs = new java.util.ArrayList<>();
        for (int i = 0; i < texts.length; i++) docs.add(new CorpusIndex.Doc("d" + i, texts[i]));
        return CorpusIndex.build(docs);
    }

    @Test
    void mapsMatchesToDocumentsAndLocalOffsets() {
        CorpusIndex idx = index("connect ECONNREFUSED 5432", "all good", "again ECONNREFUSED here");
        List<CorpusIndex.DocMatches> hits = idx.search("ECONNREFUSED", 10);
        assertEquals(2, hits.size());
        assertEquals("d0", hits.get(0).docId());
        assertArrayEquals(new int[] {8}, hits.get(0).offsets());
        assertEquals("d2", hits.get(1).docId());
        assertArrayEquals(new int[] {6}, hits.get(1).offsets());
    }

    @Test
    void searchIsCaseInsensitive() {
        CorpusIndex idx = index("We chose Postgres", "postgres is fine");
        assertEquals(2, idx.search("POSTGRES", 5).size());
        assertEquals(2, idx.count("PostGres"));
    }

    @Test
    void matchesNeverSpanTwoDocuments() {  // boundary between documents
        CorpusIndex idx = index("ends with use", "Effect starts here");
        assertEquals(0, idx.count("useeffect"));
        assertEquals(0, idx.count("use effect"));
    }

    @Test
    void emptyCorpusReturnsNoMatches() {  // boundary
        CorpusIndex idx = CorpusIndex.build(List.of());
        assertTrue(idx.search("anything", 3).isEmpty());
        assertEquals(0, idx.count("anything"));
    }

    @Test
    void rejectsInvalidInput() {
        CorpusIndex idx = index("text");
        assertThrows(IllegalArgumentException.class, () -> idx.search("   ", 3));
        assertThrows(IllegalArgumentException.class, () -> idx.search("t", -1));
        assertThrows(IllegalArgumentException.class, () -> CorpusIndex.build(List.of(
                new CorpusIndex.Doc("same", "a"), new CorpusIndex.Doc("same", "b"))));
    }

    @Test
    void capsOffsetsButKeepsExactCounts() {
        CorpusIndex idx = index("ab ab ab ab ab");
        CorpusIndex.DocMatches m = idx.search("ab", 2).get(0);
        assertEquals(5, m.count());
        assertArrayEquals(new int[] {0, 3}, m.offsets());
    }

    @Test
    void separatorCharactersInInputCannotForgeMatches() {
        CorpusIndex idx = index("a\u0001b", "c");
        assertEquals(1, idx.count("a b"));  // control character was replaced by a space
    }

    @Test
    void offsetsAlignWithOriginalTextForSpecialCharacters() {
        String text = "İstanbul ERROR";  // İ lower-cases to two chars with String.toLowerCase
        CorpusIndex idx = index(text);
        int offset = idx.search("error", 1).get(0).offsets()[0];
        assertEquals("ERROR", text.substring(offset, offset + 5));
    }
}
