package dev.notch.api.ingest.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptParserTest {

    private final TranscriptParser parser = new TranscriptParser();

    @Test
    void splitsInlineSpeakerMarkers() {
        ParsedConversation c = parser.parse("chat.txt", "User: why is login slow?\nAssistant: Missing index.\nIt scans users.\nUser: ok, thanks");
        assertEquals(3, c.messages().size());
        assertEquals("user", c.messages().get(0).role());
        assertEquals("why is login slow?", c.messages().get(0).text());
        assertEquals("assistant", c.messages().get(1).role());
        assertEquals("Missing index.\nIt scans users.", c.messages().get(1).text());
    }

    @Test
    void understandsMarkdownHeadingsAndBold() {
        String md = "# Auth refactor\n\n## User\nShould we use JWT?\n\n**Claude:** Sessions are simpler here.";
        ParsedConversation c = parser.parse("notes.md", md);
        assertEquals("Auth refactor", c.title());
        assertEquals(3, c.messages().size());  // preface heading becomes a note
        assertEquals("note", c.messages().get(0).role());
        assertEquals("Should we use JWT?", c.messages().get(1).text());
        assertEquals("Sessions are simpler here.", c.messages().get(2).text());
    }

    @Test
    void plainNoteBecomesOneMessage() {
        ParsedConversation c = parser.parse("decisions.md", "We use Redis for rate limiting.\nMe: unrelated colon line");
        assertEquals(1, c.messages().size());
        assertEquals("note", c.messages().get(0).role());
        assertEquals("decisions", c.title());
    }

    @Test
    void sameContentGivesSameIdForDuplicateDetection() {
        assertEquals(parser.parse("a.md", "hello world").externalId(), parser.parse("b.md", "hello world").externalId());
        assertNotEquals(parser.parse("a.md", "hello").externalId(), parser.parse("a.md", "hello!").externalId());
    }

    @Test
    void emptyFileHasNoMessages() {  // boundary
        ParsedConversation c = parser.parse(null, "   \n");
        assertTrue(c.messages().isEmpty());
        assertEquals("Untitled note", c.title());
    }

    @Test
    void windowsLineEndingsAreHandled() {
        ParsedConversation c = parser.parse("x.txt", "User: hi there\r\nAssistant: hello back\r\n");
        assertEquals(2, c.messages().size());
        assertEquals("hello back", c.messages().get(1).text());
    }
}
