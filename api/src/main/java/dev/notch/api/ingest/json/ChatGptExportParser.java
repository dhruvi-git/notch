package dev.notch.api.ingest.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.notch.api.ingest.core.ParsedConversation;
import dev.notch.api.ingest.core.ParsedMessage;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code conversations.json} from a ChatGPT data export.
 *
 * <p>Each conversation is a tree of message nodes (editing a message creates a branch). The
 * parser follows parent links back from {@code current_node}, which gives the branch the
 * user last saw, and keeps user and assistant messages in order.
 */
public final class ChatGptExportParser {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GptConversation(String id, @JsonProperty("conversation_id") String conversationId,
                           String title, @JsonProperty("create_time") Double createTime,
                           Map<String, GptNode> mapping, @JsonProperty("current_node") String currentNode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GptNode(String id, GptMessage message, String parent, List<String> children) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GptMessage(String id, GptAuthor author, @JsonProperty("create_time") Double createTime,
                      GptContent content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GptAuthor(String role) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GptContent(@JsonProperty("content_type") String contentType, List<Object> parts) {
    }

    private final ObjectMapper mapper;

    public ChatGptExportParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parses the export. Returns an empty list when the JSON is valid but is not a ChatGPT
     * export (no conversation has a {@code mapping}), so the caller can try another format.
     */
    public List<ParsedConversation> parse(byte[] json) {
        GptConversation[] all = mapper.readValue(json, GptConversation[].class);
        List<ParsedConversation> out = new ArrayList<>();
        for (GptConversation c : all) {
            if (c == null || c.mapping() == null || c.mapping().isEmpty()) continue;
            String id = c.conversationId() != null ? c.conversationId() : c.id();
            if (id == null) continue;
            String title = c.title() == null || c.title().isBlank() ? "Untitled chat" : c.title().strip();
            out.add(new ParsedConversation(id, title, toInstant(c.createTime()), messages(c)));
        }
        return out;
    }

    private static List<ParsedMessage> messages(GptConversation c) {
        String nodeId = c.currentNode() != null && c.mapping().containsKey(c.currentNode())
                ? c.currentNode() : latestLeaf(c.mapping());
        List<GptNode> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (nodeId != null && visited.add(nodeId)) {  // visited guards against cycles
            GptNode node = c.mapping().get(nodeId);
            if (node == null) break;
            path.add(node);
            nodeId = node.parent();
        }
        Collections.reverse(path);

        List<ParsedMessage> out = new ArrayList<>();
        for (GptNode node : path) {
            GptMessage m = node.message();
            if (m == null || m.author() == null || m.content() == null) continue;
            String role = m.author().role();
            if (!"user".equals(role) && !"assistant".equals(role)) continue;  // skip system and tool
            String text = text(m.content());
            if (text.isBlank()) continue;
            String extId = m.id() != null ? m.id() : node.id();
            if (extId == null) continue;
            out.add(new ParsedMessage(extId, role, text, toInstant(m.createTime())));
        }
        return out;
    }

    private static String text(GptContent content) {
        if (content.parts() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Object part : content.parts()) {
            String piece = null;
            if (part instanceof String s) piece = s;
            else if (part instanceof Map<?, ?> map && map.get("text") instanceof String s) piece = s;
            if (piece != null && !piece.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(piece.strip());
            }
        }
        return sb.toString();
    }

    /** Fallback when current_node is missing: the leaf with the newest message. */
    private static String latestLeaf(Map<String, GptNode> mapping) {
        String best = null;
        double bestTime = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, GptNode> e : mapping.entrySet()) {
            GptNode n = e.getValue();
            if (n == null || (n.children() != null && !n.children().isEmpty())) continue;
            double t = n.message() != null && n.message().createTime() != null ? n.message().createTime() : 0;
            if (best == null || t > bestTime) {
                best = e.getKey();
                bestTime = t;
            }
        }
        return best;
    }

    private static Instant toInstant(Double epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) return null;
        long millis = (long) (epochSeconds * 1000);
        return Instant.ofEpochMilli(millis);
    }
}
