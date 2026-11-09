package dev.notch.api.ingest.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits message text into searchable chunks of at most {@code maxChars} characters.
 *
 * <p>Paragraphs (separated by blank lines) are packed together while they fit. A paragraph
 * longer than the limit is cut at the last whitespace before the limit. Offsets always refer
 * to the original text, and chunks never start or end with whitespace.
 */
public final class Chunker {

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n");

    private final int maxChars;

    public Chunker(int maxChars) {
        if (maxChars < 50) throw new IllegalArgumentException("maxChars must be at least 50");
        this.maxChars = maxChars;
    }

    public List<Chunk> split(String text) {
        List<int[]> paragraphs = paragraphs(text);
        List<Chunk> out = new ArrayList<>();
        int curStart = -1;
        int curEnd = -1;
        for (int[] p : paragraphs) {
            if (p[1] - p[0] > maxChars) {
                if (curStart >= 0) emit(text, curStart, curEnd, out);
                curStart = -1;
                splitLong(text, p[0], p[1], out);
                continue;
            }
            if (curStart < 0) {
                curStart = p[0];
                curEnd = p[1];
            } else if (p[1] - curStart <= maxChars) {
                curEnd = p[1];
            } else {
                emit(text, curStart, curEnd, out);
                curStart = p[0];
                curEnd = p[1];
            }
        }
        if (curStart >= 0) emit(text, curStart, curEnd, out);
        return out;
    }

    /** Non-blank paragraph spans, trimmed of surrounding whitespace. */
    private static List<int[]> paragraphs(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = PARAGRAPH_BREAK.matcher(text);
        int from = 0;
        while (m.find()) {
            addTrimmed(text, from, m.start(), spans);
            from = m.end();
        }
        addTrimmed(text, from, text.length(), spans);
        return spans;
    }

    private static void addTrimmed(String text, int start, int end, List<int[]> spans) {
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (end > start) spans.add(new int[] {start, end});
    }

    private void splitLong(String text, int start, int end, List<Chunk> out) {
        int s = start;
        while (s < end) {
            int limit = Math.min(end, s + maxChars);
            int cut = limit;
            if (limit < end) {
                int ws = limit;
                while (ws > s && !Character.isWhitespace(text.charAt(ws - 1))) ws--;
                if (ws > s + maxChars / 2) cut = ws;  // avoid tiny chunks from a far-back space
            }
            emit(text, s, cut, out);
            s = cut;
            while (s < end && Character.isWhitespace(text.charAt(s))) s++;
        }
    }

    private static void emit(String text, int start, int end, List<Chunk> out) {
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (end > start) out.add(new Chunk(start, end, text.substring(start, end)));
    }
}
