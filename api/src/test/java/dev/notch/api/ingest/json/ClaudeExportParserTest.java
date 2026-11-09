package dev.notch.api.ingest.json;

import dev.notch.api.ingest.core.ParsedConversation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeExportParserTest {

    private final ClaudeExportParser parser = new ClaudeExportParser(JsonMapper.builder().build());

    @Test
    void readsMessagesFromTextOrContentBlocks() {
        String export = """
                [{"uuid":"k1","name":"Auth","created_at":"2025-10-20T09:14:03.512345+00:00","chat_messages":[
                  {"uuid":"m1","sender":"human","text":"hi","created_at":"2025-10-20T09:14:03Z"},
                  {"uuid":"m2","sender":"assistant","text":"","content":[{"type":"text","text":"hello"},{"type":"tool_use"}]}
                ]}]""";
        ParsedConversation c = parser.parse(export.getBytes(StandardCharsets.UTF_8)).get(0);
        assertEquals("Auth", c.title());
        assertEquals(List.of("user", "assistant"), c.messages().stream().map(m -> m.role()).toList());
        assertEquals("hello", c.messages().get(1).text());
        assertNotNull(c.startedAt());
    }

    @Test
    void parsesTimestampsWithAndWithoutOffsets() {
        assertNotNull(ClaudeExportParser.parseInstant("2025-10-20T09:14:03.512345+00:00"));
        assertNotNull(ClaudeExportParser.parseInstant("2025-10-20T09:14:03Z"));
        assertNull(ClaudeExportParser.parseInstant("yesterday"));
        assertNull(ClaudeExportParser.parseInstant(null));
    }

    @Test
    void chatGptExportIsNotMistakenForClaude() {
        assertTrue(parser.parse("[{\"id\":\"c\",\"mapping\":{}}]".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    void emptyConversationIsKeptWithNoMessages() {  // boundary
        ParsedConversation c = parser.parse("[{\"uuid\":\"e\",\"chat_messages\":[]}]".getBytes(StandardCharsets.UTF_8)).get(0);
        assertTrue(c.messages().isEmpty());
    }

    @Test
    void parsesTheSampleExport() throws Exception {
        byte[] sample = Files.readAllBytes(Path.of("../samples/claude-conversations.json"));
        ParsedConversation c = parser.parse(sample).get(0);
        assertEquals(6, c.messages().size());
    }
}
