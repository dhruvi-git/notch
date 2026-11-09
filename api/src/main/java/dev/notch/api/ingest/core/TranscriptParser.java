package dev.notch.api.ingest.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a pasted transcript or a markdown note as one conversation.
 *
 * <p>Lines such as {@code User: ...}, {@code **Assistant:**} or {@code ## Claude} start a new
 * message. A file with fewer than two such markers is treated as a single note. The
 * conversation id is a hash of the content, so importing the same file twice is detected.
 */
public final class TranscriptParser {

    private static final String SPEAKERS = "user|human|you|me|assistant|claude|chatgpt|gpt|ai";

    /** "User: text", "**User:** text", "## User: text", "User:" */
    private static final Pattern INLINE_MARKER = Pattern.compile(
            "^[ \\t]*(?:#{1,6}[ \\t]+)?(?:\\*\\*)?(" + SPEAKERS + ")(?:\\*\\*)?[ \\t]*:(?:\\*\\*)?[ \\t]?(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** "## User" on its own line. */
    private static final Pattern HEADING_MARKER = Pattern.compile(
            "^[ \\t]*#{1,6}[ \\t]+(" + SPEAKERS + ")[ \\t]*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern TITLE = Pattern.compile("^#[ \\t]+(.+)$");

    public ParsedConversation parse(String filename, String text) {
        String[] lines = text.split("\\r?\\n", -1);
        List<int[]> markers = new ArrayList<>();       // {line index, role code}
        List<String> firstLineRest = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher heading = HEADING_MARKER.matcher(lines[i]);
            Matcher inline = INLINE_MARKER.matcher(lines[i]);
            if (heading.matches()) {
                markers.add(new int[] {i, roleCode(heading.group(1))});
                firstLineRest.add("");
            } else if (inline.matches()) {
                markers.add(new int[] {i, roleCode(inline.group(1))});
                firstLineRest.add(inline.group(2));
            }
        }

        List<ParsedMessage> messages = new ArrayList<>();
        if (markers.size() < 2) {
            String body = text.strip();
            if (!body.isEmpty()) messages.add(new ParsedMessage("m0", "note", body, null));
        } else {
            String preface = join(lines, 0, markers.get(0)[0], null).strip();
            if (!preface.isEmpty()) messages.add(new ParsedMessage("m0", "note", preface, null));
            for (int k = 0; k < markers.size(); k++) {
                int from = markers.get(k)[0];
                int to = k + 1 < markers.size() ? markers.get(k + 1)[0] : lines.length;
                String body = join(lines, from + 1, to, firstLineRest.get(k)).strip();
                if (body.isEmpty()) continue;
                String role = markers.get(k)[1] == 0 ? "user" : "assistant";
                messages.add(new ParsedMessage("m" + messages.size(), role, body, null));
            }
        }
        String id = "sha256:" + ContentHash.hex(ContentHash.of(text));
        return new ParsedConversation(id, title(lines, filename), null, messages);
    }

    private static int roleCode(String speaker) {
        String s = speaker.toLowerCase(Locale.ROOT);
        return (s.equals("user") || s.equals("human") || s.equals("you") || s.equals("me")) ? 0 : 1;
    }

    private static String join(String[] lines, int from, int to, String firstLine) {
        StringBuilder sb = new StringBuilder();
        if (firstLine != null && !firstLine.isBlank()) sb.append(firstLine).append('\n');
        for (int i = from; i < to; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    private static String title(String[] lines, String filename) {
        for (String line : lines) {
            Matcher m = TITLE.matcher(line.strip());
            if (m.matches()) return truncate(m.group(1).strip());
        }
        if (filename != null && !filename.isBlank()) {
            String base = filename.replaceAll("^.*[/\\\\]", "");
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            if (!base.isBlank()) return truncate(base);
        }
        return "Untitled note";
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 199) + "…";
    }
}
