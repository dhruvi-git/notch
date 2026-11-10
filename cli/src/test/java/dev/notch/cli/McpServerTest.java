package dev.notch.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    private final FakeBackend backend = new FakeBackend();
    private final McpServer server = new McpServer(backend, new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(new ByteArrayOutputStream()));

    private Map<String, Object> reply(String request) {
        return Json.obj(Json.parse(server.handle(request)));
    }

    private String toolText(String name, String args) {
        Map<String, Object> r = Json.obj(reply("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\",\"arguments\":" + args + "}}").get("result"));
        return (String) Json.obj(Json.arr(r.get("content")).get(0)).get("text");
    }

    @Test
    void initializeEchoesTheProtocolVersionAndOffersTools() {
        Map<String, Object> r = reply("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        assertEquals("2.0", r.get("jsonrpc"));
        assertEquals(1.0, r.get("id"));
        Map<String, Object> result = Json.obj(r.get("result"));
        assertEquals("2025-06-18", result.get("protocolVersion"));
        assertTrue(Json.obj(result.get("capabilities")).containsKey("tools"));
        assertEquals("notch", Json.obj(result.get("serverInfo")).get("name"));
    }

    @Test
    void notificationsGetNoReply() {
        assertNull(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }

    @Test
    void listsTheSixTools() {
        List<Object> tools = Json.arr(Json.obj(reply("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").get("result")).get("tools"));
        assertEquals(List.of("notch_decisions", "notch_check_change", "notch_search", "notch_where_we_stopped",
                "notch_record_decision", "notch_add_task"), tools.stream().map(t -> Json.obj(t).get("name")).toList());
        for (Object t : tools) assertEquals("object", Json.obj(Json.obj(t).get("inputSchema")).get("type"));
    }

    @Test
    void checkChangeCatchesARuledOutDependency() {
        String text = toolText("notch_check_change", "{\"file_path\":\"package.json\",\"content\":\"\\\"mongoose\\\": \\\"8\\\"\"}");
        assertTrue(text.startsWith("This change goes against project decisions"), text);
        assertTrue(text.contains("[HIGH] package.json:1"));
        assertTrue(toolText("notch_check_change", "{\"file_path\":\"package.json\",\"content\":\"\\\"pg\\\": \\\"8\\\"\"}").startsWith("No conflicts"));
    }

    @Test
    void decisionsCanBeFilteredByTopic() {
        assertTrue(toolText("notch_decisions", "{}").contains("ruled out: MongoDB"));
        assertTrue(toolText("notch_decisions", "{\"topic\":\"auth\"}").startsWith("No decisions mention"));
    }

    @Test
    void recordDecisionPassesEverythingThrough() {
        String text = toolText("notch_record_decision", "{\"decision\":\"Use Redis for sessions.\",\"why\":\"fast\",\"ruled_out\":[\"Memcached\"]}");
        assertTrue(text.startsWith("Recorded decision m1"));
        assertEquals("DECISION: Use Redis for sessions. | fast | [Memcached]", backend.recorded.get(0));
    }

    @Test
    void toolErrorsAreReportedToTheAgentNotThrown() {  // invalid input and outages
        Map<String, Object> r = Json.obj(reply("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"notch_search\",\"arguments\":{}}}").get("result"));
        assertEquals(Boolean.TRUE, r.get("isError"));
        backend.down = true;
        assertTrue(toolText("notch_decisions", "{}").contains("Couldn't reach Notch"));
    }

    @Test
    void protocolErrorsUseJsonRpcCodes() {
        assertEquals(-32601.0, Json.obj(reply("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}").get("error")).get("code"));
        assertEquals(-32700.0, Json.obj(reply("not json").get("error")).get("code"));
    }

    @Test
    void stdioLoopWritesOneLinePerReply() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        McpServer s = new McpServer(backend, new PrintStream(bytes, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        s.serve(new java.io.BufferedReader(new java.io.StringReader(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n")));
        String[] lines = bytes.toString(StandardCharsets.UTF_8).strip().split("\n");
        assertEquals(2, lines.length);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", lines[0]);
    }
}
