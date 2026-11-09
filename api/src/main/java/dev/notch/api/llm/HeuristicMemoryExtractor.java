package dev.notch.api.llm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline memory extraction from cue phrases. Used in tests and development, and always for
 * private projects, whose text must never reach a language model.
 *
 * <ul>
 *   <li><b>Decisions:</b> sentences such as "let's go with Postgres because …"; "because" gives
 *       the rationale, "instead of" / "rather than" give rejected options.</li>
 *   <li><b>Tasks:</b> unchecked checklist items and phrases such as "next step" or "still need to".</li>
 *   <li><b>Fixes:</b> a user message naming an error, followed by an assistant reply that fixes it.</li>
 * </ul>
 * It favours precision: a missed memory costs less than a wrong one in the decision ledger.
 */
public final class HeuristicMemoryExtractor implements LlmClient {

    static final int MAX_PER_CONVERSATION = 50;
    static final int MAX_TITLE = 140;

    private static final String[] DECISION_CUES = {
            "we decided", "decided to", "decision:", "let's go with", "lets go with", "we'll go with",
            "we will go with", "we'll use", "we will use", "going with", "we chose", "i chose",
            "chose to", "we're switching to", "switching to", "settled on", "agreed to", "we picked"};

    private static final String[] TASK_CUES = {
            "todo:", "to do:", "next step", "still need to", "remaining:", "follow up on",
            "follow-up:", "need to add", "need to fix", "need to write", "don't forget to"};

    private static final String[] FIX_CUES = {
            "the fix", "to fix", "fixed", "the issue is", "the issue was", "the problem is",
            "the problem was", "caused by", "solution", "resolve", "change ", "replace ", "add "};

    /** Error names are matched case-sensitively, so ordinary words like "Every" never count. */
    private static final Pattern ERROR_SIGNATURE = Pattern.compile(
            "\\b[A-Z][A-Za-z]*(?:Error|Exception)\\b|\\bE[A-Z]{4,}\\b|\\b[45]\\d\\d (?:[A-Z][a-z]+ ?)+"
                    + "|(?i:\\b(?:traceback|stack trace|segfault|panicked|failed to|cannot find|is not defined)\\b)");

    private static final Pattern REJECTED = Pattern.compile(
            "\\b(?:instead of|rather than)\\s+([A-Za-z0-9_.+#/-]+(?:\\s+(?!(?:because|since|so|as|and|but|for|to|which|that|we|it|with)\\b)[A-Za-z0-9_.+#/-]+){0,2})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RATIONALE = Pattern.compile(
            "\\b(?:because|since|so that|as it|given that)\\b\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    @Override
    public List<ExtractedMemory> extractMemories(ExtractionRequest request) {
        List<ExtractedMemory> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ExtractionRequest.SourceMessage> msgs = request.messages();
        for (int i = 0; i < msgs.size() && out.size() < MAX_PER_CONVERSATION; i++) {
            ExtractionRequest.SourceMessage m = msgs.get(i);
            for (int[] s : sentences(m.text())) {
                String sentence = m.text().substring(s[0], s[1]);
                String lower = sentence.toLowerCase(Locale.ROOT);
                if (containsAny(lower, DECISION_CUES) && !lower.endsWith("?")) {
                    add(out, seen, decision(sentence, i, s[0], s[1]));
                } else if (isTask(sentence, lower)) {
                    add(out, seen, new ExtractedMemory(MemoryKind.TASK, title(stripBullet(sentence)),
                            null, null, List.of(), i, s[0], s[1]));
                }
            }
            if (m.role().equals("user") && i + 1 < msgs.size()
                    && msgs.get(i + 1).role().equals("assistant")) {
                ExtractedMemory fix = fix(m.text(), msgs.get(i + 1).text(), i);
                if (fix != null) add(out, seen, fix);
            }
        }
        return out.size() > MAX_PER_CONVERSATION ? out.subList(0, MAX_PER_CONVERSATION) : out;
    }

    private static ExtractedMemory decision(String sentence, int msg, int start, int end) {
        String rationale = null;
        Matcher r = RATIONALE.matcher(sentence);
        if (r.find()) rationale = trimPunctuation(r.group(1));
        List<String> rejected = new ArrayList<>();
        Matcher x = REJECTED.matcher(sentence);
        while (x.find()) rejected.add(trimPunctuation(x.group(1)));
        return new ExtractedMemory(MemoryKind.DECISION, title(sentence), null, rationale,
                rejected, msg, start, end);
    }

    private static boolean isTask(String sentence, String lower) {
        String t = sentence.strip();
        return t.startsWith("- [ ]") || t.startsWith("* [ ]") || containsAny(lower, TASK_CUES);
    }

    private static ExtractedMemory fix(String userText, String reply, int userIndex) {
        Matcher e = ERROR_SIGNATURE.matcher(userText);
        if (!e.find()) return null;
        String replyLower = reply.toLowerCase(Locale.ROOT);
        if (!containsAny(replyLower, FIX_CUES)) return null;
        int lineStart = userText.lastIndexOf('\n', e.start()) + 1;
        int lineEnd = userText.indexOf('\n', e.end());
        if (lineEnd < 0) lineEnd = userText.length();
        String errorLine = userText.substring(lineStart, lineEnd).strip();
        String body = reply.strip();
        if (body.length() > 600) body = body.substring(0, 599) + "…";
        return new ExtractedMemory(MemoryKind.FIX, title("Fix: " + errorLine), body, null,
                List.of(), userIndex, lineStart, lineEnd);
    }

    /** Sentence spans {start, end}, trimmed. */
    static List<int[]> sentences(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = SENTENCE_END.matcher(text);
        int from = 0;
        while (m.find()) {
            addSpan(text, from, m.start(), spans);
            from = m.end();
        }
        addSpan(text, from, text.length(), spans);
        return spans;
    }

    private static void addSpan(String text, int s, int e, List<int[]> spans) {
        while (s < e && Character.isWhitespace(text.charAt(s))) s++;
        while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
        if (e - s >= 8) spans.add(new int[] {s, e});
    }

    private static void add(List<ExtractedMemory> out, Set<String> seen, ExtractedMemory m) {
        String key = m.kind() + ":" + m.title().toLowerCase(Locale.ROOT).replaceAll("\\W+", " ").strip();
        if (seen.add(key)) out.add(m);
    }

    private static boolean containsAny(String lower, String[] cues) {
        for (String c : cues) if (lower.contains(c)) return true;
        return false;
    }

    private static String stripBullet(String s) {
        return s.strip().replaceFirst("^[-*]\\s*\\[ \\]\\s*", "").replaceFirst("^(?i)(todo|to do):\\s*", "");
    }

    private static String trimPunctuation(String s) {
        return s.strip().replaceAll("[.,;:!]+$", "").strip();
    }

    static String title(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= MAX_TITLE ? t : t.substring(0, MAX_TITLE - 1) + "…";
    }

    @Override
    public String name() {
        return "offline";
    }
}
