package dev.notch.api.ingest.json;

import dev.notch.api.ingest.core.ParsedConversation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatGptExportParserTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ChatGptExportParser parser = new ChatGptExportParser(mapper);

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void followsCurrentNodeBranchAndSkipsSystemMessages() {
        String export = """
                [{"conversation_id":"c1","title":"Test","create_time":1700000000.5,"current_node":"b2",
                  "mapping":{
                    "root":{"id":"root","message":null,"parent":null,"children":["s"]},
                    "s":{"id":"s","message":{"id":"s","author":{"role":"system"},"content":{"content_type":"text","parts":["sys"]}},"parent":"root","children":["u"]},
                    "u":{"id":"u","message":{"id":"u","author":{"role":"user"},"create_time":1700000001,"content":{"content_type":"text","parts":["hello"]}},"parent":"s","children":["a1","b2"]},
                    "a1":{"id":"a1","message":{"id":"a1","author":{"role":"assistant"},"content":{"content_type":"text","parts":["old branch"]}},"parent":"u","children":[]},
                    "b2":{"id":"b2","message":{"id":"b2","author":{"role":"assistant"},"content":{"content_type":"text","parts":["new branch"]}},"parent":"u","children":[]}
                  }}]""";
        List<ParsedConversation> out = parser.parse(json(export));
        assertEquals(1, out.size());
        ParsedConversation c = out.get(0);
        assertEquals("c1", c.externalId());
        assertEquals(2, c.messages().size());
        assertEquals("hello", c.messages().get(0).text());
        assertEquals("new branch", c.messages().get(1).text());
        assertEquals(1700000000500L, c.startedAt().toEpochMilli());
    }

    @Test
    void joinsMultipartAndObjectParts() {
        String export = """
                [{"id":"c2","title":"","mapping":{
                  "u":{"id":"u","message":{"id":"u","author":{"role":"user"},"content":{"content_type":"multimodal_text",
                     "parts":[{"asset_pointer":"file-1"},"look at this",{"text":"and this"}]}},"parent":null,"children":[]}
                }}]""";
        ParsedConversation c = parser.parse(json(export)).get(0);
        assertEquals("Untitled chat", c.title());
        assertEquals("look at this\n\nand this", c.messages().get(0).text());
    }

    @Test
    void claudeExportIsNotMistakenForChatGpt() {
        assertTrue(parser.parse(json("[{\"uuid\":\"x\",\"chat_messages\":[]}]")).isEmpty());
    }

    @Test
    void emptyArrayGivesNoConversations() {  // boundary
        assertTrue(parser.parse(json("[]")).isEmpty());
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(RuntimeException.class, () -> parser.parse(json("[{\"mapping\": ")));
    }

    @Test
    void parsesTheSampleExport() throws Exception {
        byte[] sample = Files.readAllBytes(Path.of("../samples/chatgpt-conversations.json"));
        List<ParsedConversation> out = parser.parse(sample);
        assertEquals(2, out.size());
        assertEquals(7, out.get(0).messages().size());
    }
}
