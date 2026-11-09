package dev.notch.api.ingest.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {

    @Test
    void packsShortParagraphsTogether() {
        String text = "First paragraph.\n\nSecond paragraph.\n\nThird.";
        List<Chunk> chunks = new Chunker(100).split(text);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0).text());
    }

    @Test
    void startsNewChunkWhenNextParagraphDoesNotFit() {
        String a = "a".repeat(60), b = "b".repeat(60);
        List<Chunk> chunks = new Chunker(100).split(a + "\n\n" + b);
        assertEquals(2, chunks.size());
        assertEquals(a, chunks.get(0).text());
        assertEquals(b, chunks.get(1).text());
    }

    @Test
    void offsetsPointIntoOriginalText() {
        String text = "  intro line  \n\n\n  body text here  ";
        for (Chunk c : new Chunker(60).split(text)) {
            assertEquals(c.text(), text.substring(c.start(), c.end()));
            assertFalse(Character.isWhitespace(c.text().charAt(0)));
        }
    }

    @Test
    void longParagraphIsCutAtWhitespace() {
        String word = "token ";
        String text = word.repeat(100).strip();  // 599 chars, one paragraph
        List<Chunk> chunks = new Chunker(100).split(text);
        assertTrue(chunks.size() >= 6);
        for (Chunk c : chunks) {
            assertTrue(c.text().length() <= 100);
            assertTrue(c.text().startsWith("token") && c.text().endsWith("token"), c.text());
        }
    }

    @Test
    void wordLongerThanLimitIsHardCut() {  // boundary
        String text = "x".repeat(250);
        List<Chunk> chunks = new Chunker(100).split(text);
        assertEquals(List.of(100, 100, 50), chunks.stream().map(c -> c.text().length()).toList());
    }

    @Test
    void blankTextGivesNoChunks() {  // boundary
        assertTrue(new Chunker(100).split("").isEmpty());
        assertTrue(new Chunker(100).split(" \n\n \t ").isEmpty());
    }

    @Test
    void rejectsTinyLimit() {
        assertThrows(IllegalArgumentException.class, () -> new Chunker(10));
    }

    @Test
    void estimatesTokens() {
        assertEquals(3, new Chunk(0, 10, "0123456789").estimatedTokens());
    }
}
