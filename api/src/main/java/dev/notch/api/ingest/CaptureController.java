package dev.notch.api.ingest;

import dev.notch.api.config.ApiException;
import dev.notch.api.ingest.core.ImportFormat;

import dev.notch.api.ingest.core.ParsedConversation;
import dev.notch.api.ingest.core.ParsedMessage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Automatic capture from the CLI: Claude Code sessions and git history, without exports. */
@RestController
public class CaptureController {

    static final int MAX_CONVERSATIONS = 50;
    static final int MAX_MESSAGES = 5_000;
    static final long MAX_CHARS = 8_000_000;
    static final Set<String> ROLES = Set.of("user", "assistant", "note");

    public record CapturedMessage(String externalId, String role, String text, Instant sentAt) {
    }

    public record CapturedConversation(String externalId, String title, Instant startedAt, List<CapturedMessage> messages) {
    }

    public record CaptureRequest(String source, String label, List<CapturedConversation> conversations) {
    }

    private final ImportService imports;

    public CaptureController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/api/projects/{projectId}/captures")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportReport capture(@PathVariable UUID projectId, @RequestBody CaptureRequest req) {
        ImportFormat format = req == null ? null : ImportFormat.capturedFrom(req.source());
        if (format == null) throw ApiException.badRequest("unknown_source", "Captures come from claude-code or git.");
        if (req.conversations() == null || req.conversations().isEmpty()) {
            throw ApiException.badRequest("no_messages", "Nothing to capture.");
        }
        if (req.conversations().size() > MAX_CONVERSATIONS) {
            throw ApiException.badRequest("too_many", "Send at most " + MAX_CONVERSATIONS + " conversations at a time.");
        }
        List<ParsedConversation> parsed = new ArrayList<>();
        int messages = 0;
        long chars = 0;
        for (CapturedConversation c : req.conversations()) {
            if (c.externalId() == null || c.externalId().isBlank() || c.messages() == null) {
                throw ApiException.badRequest("invalid_capture", "Every conversation needs an id and messages.");
            }
            List<ParsedMessage> ms = new ArrayList<>();
            for (CapturedMessage m : c.messages()) {
                if (m.externalId() == null || m.text() == null || m.text().isBlank()) continue;
                if (!ROLES.contains(m.role())) throw ApiException.badRequest("invalid_capture", "Roles are user, assistant or note.");
                ms.add(new ParsedMessage(m.externalId(), m.role(), m.text(), m.sentAt()));
                messages++;
                chars += m.text().length();
            }
            if (!ms.isEmpty()) {
                String title = c.title() == null || c.title().isBlank() ? "Captured session" : c.title().strip();
                parsed.add(new ParsedConversation(c.externalId(), title.length() > 200 ? title.substring(0, 200) : title,
                        c.startedAt(), ms));
            }
        }
        if (messages > MAX_MESSAGES || chars > MAX_CHARS) {
            throw ApiException.tooLarge("That capture is too large; notch sync sends it in smaller batches.");
        }
        if (parsed.isEmpty()) throw ApiException.badRequest("no_messages", "Nothing to capture.");
        String label = req.label() == null || req.label().isBlank() ? format.source() : req.label().strip();
        return imports.importCaptured(projectId, format, label.length() > 200 ? label.substring(0, 200) : label, parsed);
    }
}
