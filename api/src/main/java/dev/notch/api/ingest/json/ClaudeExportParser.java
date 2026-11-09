package dev.notch.api.ingest.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.notch.api.ingest.core.ParsedConversation;
import dev.notch.api.ingest.core.ParsedMessage;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Reads {@code conversations.json} from a Claude data export. */
public final class ClaudeExportParser {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClaudeConversation(String uuid, String name, @JsonProperty("created_at") String createdAt,
                              @JsonProperty("chat_messages") List<ClaudeMessage> chatMessages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClaudeMessage(String uuid, String text, String sender,
                         @JsonProperty("created_at") String createdAt, List<ClaudeContent> content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClaudeContent(String type, String text) {
    }

    private final ObjectMapper mapper;

    public ClaudeExportParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Returns an empty list when the JSON is valid but has no {@code chat_messages}. */
    public List<ParsedConversation> parse(byte[] json) {
        ClaudeConversation[] all = mapper.readValue(json, ClaudeConversation[].class);
        List<ParsedConversation> out = new ArrayList<>();
        for (ClaudeConversation c : all) {
            if (c == null || c.uuid() == null || c.chatMessages() == null) continue;
            String title = c.name() == null || c.name().isBlank() ? "Untitled chat" : c.name().strip();
            List<ParsedMessage> messages = new ArrayList<>();
            int i = 0;
            for (ClaudeMessage m : c.chatMessages()) {
                i++;
                if (m == null) continue;
                String role = "human".equals(m.sender()) ? "user"
                        : "assistant".equals(m.sender()) ? "assistant" : null;
                if (role == null) continue;
                String text = text(m);
                if (text.isBlank()) continue;
                String extId = m.uuid() != null ? m.uuid() : c.uuid() + "#" + i;
                messages.add(new ParsedMessage(extId, role, text, parseInstant(m.createdAt())));
            }
            out.add(new ParsedConversation(c.uuid(), title, parseInstant(c.createdAt()), messages));
        }
        return out;
    }

    private static String text(ClaudeMessage m) {
        if (m.text() != null && !m.text().isBlank()) return m.text().strip();
        if (m.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ClaudeContent part : m.content()) {
            if (part != null && "text".equals(part.type()) && part.text() != null && !part.text().isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(part.text().strip());
            }
        }
        return sb.toString();
    }

    static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
