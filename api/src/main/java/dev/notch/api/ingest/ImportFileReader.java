package dev.notch.api.ingest;

import dev.notch.api.config.ApiException;
import dev.notch.api.ingest.core.FileSniffer;
import dev.notch.api.ingest.core.ImportFormat;
import dev.notch.api.ingest.core.ParsedConversation;
import dev.notch.api.ingest.core.TranscriptParser;
import dev.notch.api.ingest.json.ChatGptExportParser;
import dev.notch.api.ingest.json.ClaudeExportParser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

/**
 * Turns an uploaded file into conversations. Zip archives are unpacked, JSON is tried as a
 * ChatGPT export then a Claude export, and anything else is read as a transcript or note.
 */
@Component
public class ImportFileReader {

    public record Result(ImportFormat format, List<ParsedConversation> conversations) {
    }

    private final ChatGptExportParser chatGpt;
    private final ClaudeExportParser claude;
    private final TranscriptParser transcript = new TranscriptParser();

    public ImportFileReader(ObjectMapper mapper) {
        this.chatGpt = new ChatGptExportParser(mapper);
        this.claude = new ClaudeExportParser(mapper);
    }

    public Result read(String filename, byte[] content, String requestedFormat) {
        ImportFormat requested;
        try {
            requested = ImportFormat.parseOrNull(requestedFormat);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_format", e.getMessage());
        }
        try {
            FileSniffer.Container container = FileSniffer.sniff(content);
            if (container == FileSniffer.Container.ZIP) {
                content = FileSniffer.extractConversationsJson(content);
                container = FileSniffer.Container.JSON;
            }
            boolean wantsText = requested == ImportFormat.MARKDOWN || requested == ImportFormat.TEXT;
            if (container == FileSniffer.Container.JSON && !wantsText) {
                return readJson(content, requested);
            }
            String text = FileSniffer.decodeText(content);
            ImportFormat format = requested != null ? requested : isMarkdown(filename)
                    ? ImportFormat.MARKDOWN : ImportFormat.TEXT;
            return new Result(format, List.of(transcript.parse(filename, text)));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unreadable_file", capitalize(e.getMessage()) + ".");
        }
    }

    private Result readJson(byte[] json, ImportFormat requested) {
        if (requested != ImportFormat.CLAUDE) {
            List<ParsedConversation> gpt = tryParse(() -> chatGpt.parse(json));
            if (gpt != null && !gpt.isEmpty()) return new Result(ImportFormat.CHATGPT, gpt);
        }
        if (requested != ImportFormat.CHATGPT) {
            List<ParsedConversation> cl = tryParse(() -> claude.parse(json));
            if (cl != null && !cl.isEmpty()) return new Result(ImportFormat.CLAUDE, cl);
        }
        throw ApiException.badRequest("unrecognized_export",
                "This JSON isn't a ChatGPT or Claude export. Upload conversations.json or the export zip.");
    }

    /** Jackson 3 reports bad JSON with unchecked exceptions; any of them means "not this format". */
    private static List<ParsedConversation> tryParse(java.util.function.Supplier<List<ParsedConversation>> parse) {
        try {
            return parse.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isMarkdown(String filename) {
        if (filename == null) return false;
        String f = filename.toLowerCase(Locale.ROOT);
        return f.endsWith(".md") || f.endsWith(".markdown");
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "The file could not be read" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
