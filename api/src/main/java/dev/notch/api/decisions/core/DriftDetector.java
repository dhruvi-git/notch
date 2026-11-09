package dev.notch.api.decisions.core;

import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.search.core.ProjectIndex;
import dev.notch.engine.text.Kmp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds work that goes against active decisions, in two passes.
 *
 * <ol>
 *   <li><b>Decision against decision.</b> A newer decision that chooses what an older one
 *       rejected, rejects what it chose, or picks a different tool of the same kind.</li>
 *   <li><b>Usage drift.</b> For each tool a decision rejected, the suffix array finds every
 *       chunk mentioning it. Chunks after the decision whose sentence starts using the tool
 *       ("npm install mongoose", "set up MongoDB") are flagged. A drift inside the same thread
 *       as the decision (union-find over shared files and errors) is rated HIGH.</li>
 * </ol>
 * The rules favour precision: sentences that mention the tool only to dismiss it ("instead of
 * MongoDB") are ignored.
 */
public final class DriftDetector {

    static final Duration GRACE = Duration.ofMinutes(1);
    static final int MAX_ALERTS = 100;

    private static final String[] USAGE_CUES = {
            "npm i ", "npm install", "pnpm add", "yarn add", "pip install", "poetry add", "docker run",
            "install ", "installing", "set up", "setting up", "setup ", "configure", "configuring",
            "connect to", "connecting to", "connection string", "using ", "use ", "add ", "adding",
            "migrate to", "migrating to", "switch to", "switching to", "spin up", "create a ", "import ",
            "client = ", "new "};
    private static final Pattern NEGATED = Pattern.compile(
            "\\b(?:instead of|rather than|not|no|avoid|avoiding|don't|do not|without|over|dropped|drop|"
                    + "replace|replaced|replacing|removed|remove|away from|from)\\s+(?:\\w+\\s+){0,2}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\n+");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    public List<DriftAlert> detect(List<DecisionItem> active, ProjectIndex index) {
        Map<String, DriftAlert> alerts = new LinkedHashMap<>();
        List<DecisionItem> ordered = new ArrayList<>(active);
        ordered.sort(Comparator.comparing(DecisionItem::decidedAt));
        Map<UUID, DecisionFacts> facts = new LinkedHashMap<>();
        for (DecisionItem d : ordered) facts.put(d.id(), DecisionFacts.of(d.title(), d.rejectedOptions()));

        for (int i = 0; i < ordered.size(); i++) {
            for (int j = i + 1; j < ordered.size(); j++) {
                DriftAlert a = conflict(ordered.get(i), facts.get(ordered.get(i).id()),
                        ordered.get(j), facts.get(ordered.get(j).id()));
                if (a != null) alerts.putIfAbsent(a.key(), a);
            }
        }
        // A sentence that is itself a recorded decision is judged by the decision-vs-decision pass.
        java.util.Set<UUID> decisionMessages = new java.util.HashSet<>();
        for (DecisionItem d : ordered) if (d.sourceMessageId() != null) decisionMessages.add(d.sourceMessageId());
        for (DecisionItem d : ordered) {
            for (DriftAlert a : usageDrift(d, facts.get(d.id()), index, decisionMessages)) alerts.putIfAbsent(a.key(), a);
        }
        List<DriftAlert> out = new ArrayList<>(alerts.values());
        out.sort(Comparator.comparing((DriftAlert a) -> a.severity())
                .thenComparing(a -> a.evidence() != null ? a.evidence().sentAt() : a.decidedAt(), Comparator.reverseOrder()));
        return out.size() > MAX_ALERTS ? out.subList(0, MAX_ALERTS) : out;
    }

    static DriftAlert conflict(DecisionItem older, DecisionFacts of, DecisionItem newer, DecisionFacts nf) {
        String when = DAY.format(older.decidedAt());
        for (TechCatalog.Tech t : nf.chosen()) {
            if (of.rejected().contains(t)) {
                return decisionAlert(older, newer, DriftAlert.Severity.HIGH, "This decision chooses " + t.name()
                        + ", which the " + when + " decision ruled out. Mark one of them as replaced.");
            }
        }
        for (TechCatalog.Tech t : of.chosen()) {
            if (nf.rejected().contains(t)) {
                return decisionAlert(older, newer, DriftAlert.Severity.HIGH, "This decision drops " + t.name()
                        + ", which the " + when + " decision chose. If it's a change of plan, mark the earlier one as replaced.");
            }
        }
        if (of.category() != null && of.category().equals(nf.category())
                && nf.chosen().stream().noneMatch(of.chosen()::contains)) {
            return decisionAlert(older, newer, DriftAlert.Severity.MEDIUM, "Both decisions pick a " + of.category()
                    + ": " + of.chosen().get(0).name() + " (" + when + ") and " + nf.chosen().get(0).name()
                    + ". Check that both are meant to stand.");
        }
        return null;
    }

    private static DriftAlert decisionAlert(DecisionItem older, DecisionItem newer, DriftAlert.Severity s, String message) {
        return new DriftAlert(key("conflict", older.id().toString(), newer.id().toString()),
                DriftAlert.Type.DECISION_CONFLICT, s, older.id(), older.title(), older.decidedAt(), newer.id(), null, message);
    }

    static List<DriftAlert> usageDrift(DecisionItem d, DecisionFacts f, ProjectIndex index,
                                       java.util.Set<UUID> decisionMessages) {
        List<DriftAlert> out = new ArrayList<>();
        if (f.rejected().isEmpty()) return out;
        UUID decisionThread = d.conversationId() == null ? null : safeThread(index, d.conversationId());
        String chosenName = f.chosen().isEmpty() ? null : f.chosen().get(0).name();
        for (TechCatalog.Tech rejected : f.rejected()) {
            Map<UUID, IndexedChunk> seen = new LinkedHashMap<>();
            for (String alias : TechCatalog.aliases(rejected)) {
                for (IndexedChunk c : index.chunksContaining(alias)) seen.putIfAbsent(c.chunkId(), c);
            }
            for (IndexedChunk c : seen.values()) {
                if (!c.sentAt().isAfter(d.decidedAt().plus(GRACE))) continue;
                if (c.messageId() != null && decisionMessages.contains(c.messageId())) continue;
                int[] span = usageSentence(c.text(), rejected);
                if (span == null) continue;
                boolean sameThread = decisionThread != null && decisionThread.equals(safeThread(index, c.conversationId()));
                String when = DAY.format(d.decidedAt());
                String message = "This conversation starts using " + rejected.name() + ", but on " + when
                        + " the project " + (chosenName != null ? "chose " + chosenName + " instead." : "ruled it out.");
                DriftAlert.Evidence ev = new DriftAlert.Evidence(c.chunkId(), c.conversationId(), c.conversationTitle(),
                        c.sentAt(), c.text().substring(span[0], span[1]), span[0], span[1]);
                out.add(new DriftAlert(key("usage", d.id().toString(), c.chunkId().toString()), DriftAlert.Type.USAGE_DRIFT,
                        sameThread ? DriftAlert.Severity.HIGH : DriftAlert.Severity.MEDIUM, d.id(), d.title(),
                        d.decidedAt(), null, ev, message));
            }
        }
        return out;
    }

    /** The first sentence that mentions the tool together with a usage cue, not negated. */
    static int[] usageSentence(String text, TechCatalog.Tech tech) {
        for (int[] s : sentences(text)) {
            String sentence = text.substring(s[0], s[1]);
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (!TechCatalog.find(sentence).contains(tech)) continue;
            if (!hasCue(lower)) continue;
            if (negatedEverywhere(sentence, tech)) continue;
            return s;
        }
        return null;
    }

    private static boolean hasCue(String lower) {
        for (String cue : USAGE_CUES) if (lower.contains(cue)) return true;
        return false;
    }

    /** True when every mention of the tool is preceded by a dismissal like "instead of". */
    private static boolean negatedEverywhere(String sentence, TechCatalog.Tech tech) {
        boolean anyPositive = false;
        for (String alias : TechCatalog.aliases(tech)) {
            for (int at : Kmp.findAll(sentence, alias, true)) {
                boolean wordStart = at == 0 || !Character.isLetterOrDigit(sentence.charAt(at - 1));
                int end = at + alias.length();
                boolean wordEnd = end >= sentence.length() || !Character.isLetterOrDigit(sentence.charAt(end));
                if (!wordStart || !wordEnd) continue;
                Matcher m = NEGATED.matcher(sentence.substring(Math.max(0, at - 40), at));
                if (!m.find()) anyPositive = true;
            }
        }
        return !anyPositive;
    }

    private static List<int[]> sentences(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = SENTENCE_END.matcher(text);
        int from = 0;
        while (m.find()) {
            add(text, from, m.start(), spans);
            from = m.end();
        }
        add(text, from, text.length(), spans);
        return spans;
    }

    private static void add(String text, int s, int e, List<int[]> spans) {
        while (s < e && Character.isWhitespace(text.charAt(s))) s++;
        while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
        if (e > s) spans.add(new int[] {s, e});
    }

    private static UUID safeThread(ProjectIndex index, UUID conversationId) {
        try {
            return index.threadOf(conversationId);
        } catch (IllegalArgumentException e) {
            return null;  // conversation has no indexed chunks
        }
    }

    static String key(String... parts) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
