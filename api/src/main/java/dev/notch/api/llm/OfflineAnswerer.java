package dev.notch.api.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Answers by quoting: it picks the source sentences that best match the question and cites
 * each one. It never writes a claim of its own, so every sentence it returns is grounded by
 * construction. Used offline, in tests and for private projects.
 */
public final class OfflineAnswerer {

    static final int MAX_SENTENCES = 4;
    static final int MAX_PER_CHUNK = 2;
    static final String NOTHING_FOUND = "I couldn't find anything in this project that answers that. "
            + "Try other words, or import the conversation where it was discussed.";

    private static final String[] DECISION_CUES = {
            "decided", "decision", "go with", "going with", "we'll use", "we will use", "chose",
            "settled on", "agreed", "switching to", "picked"};

    private record Candidate(int contextIndex, int n, int start, int end, String sentence, int score) {
    }

    public AnswerDraft answer(AnswerRequest req) {
        List<String> patterns = req.terms().patterns();
        List<String> expansions = req.terms().expansions();
        boolean decisionIntent = req.terms().decisionIntent();
        List<Candidate> candidates = new ArrayList<>();
        List<Candidate> decisions = new ArrayList<>();
        for (int ci = 0; ci < req.contexts().size(); ci++) {
            AnswerRequest.Context ctx = req.contexts().get(ci);
            for (int[] s : HeuristicMemoryExtractor.sentences(ctx.text())) {
                String sentence = ctx.text().substring(s[0], s[1]);
                String lower = sentence.toLowerCase(Locale.ROOT).strip();
                if (lower.endsWith("?")) continue;  // a question is never an answer
                int score = score(lower, patterns, expansions, decisionIntent, ctx.decision());
                if (score <= 0) continue;
                Candidate c = new Candidate(ci, ctx.n(), s[0], s[1], sentence, score);
                candidates.add(c);
                if (isDecisionSentence(lower)) decisions.add(c);
            }
        }
        // "What did we decide ...?" is answered with decisions when there are any.
        if (decisionIntent && !decisions.isEmpty()) candidates = decisions;
        if (candidates.isEmpty()) return new AnswerDraft(NOTHING_FOUND, List.of(), false);

        // Best sentences first, ties broken by context rank; at most two per chunk.
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparingInt(Candidate::contextIndex).thenComparingInt(Candidate::start));
        List<Candidate> chosen = new ArrayList<>();
        Map<Integer, Integer> perChunk = new HashMap<>();
        for (Candidate c : candidates) {
            if (chosen.size() == MAX_SENTENCES) break;
            if (perChunk.merge(c.contextIndex(), 1, Integer::sum) > MAX_PER_CHUNK) continue;
            chosen.add(c);
        }
        // Present in source rank order so the answer reads like the evidence.
        chosen.sort(Comparator.comparingInt(Candidate::contextIndex).thenComparingInt(Candidate::start));

        Map<Integer, Integer> renumber = new LinkedHashMap<>();
        List<AnswerDraft.Citation> citations = new ArrayList<>();
        StringBuilder text = new StringBuilder(decisionIntent
                ? "Here's what this project decided:" : "Here's what I found in this project:");
        for (Candidate c : chosen) {
            int number = renumber.computeIfAbsent(c.contextIndex(), k -> renumber.size() + 1);
            AnswerRequest.Context ctx = req.contexts().get(c.contextIndex());
            citations.add(new AnswerDraft.Citation(number, ctx.chunkId(), c.start(), c.end()));
            text.append("\n- ").append(clean(c.sentence())).append(" [").append(number).append(']');
        }
        return new AnswerDraft(text.toString(), citations, true);
    }

    /** Typed terms count 2 each, expansions 1; decision sentences get a bonus when relevant. */
    private static int score(String lower, List<String> patterns, List<String> expansions,
                             boolean decisionIntent, boolean decisionChunk) {
        int score = 0;
        for (String p : patterns) if (lower.contains(p)) score += 2;
        for (String e : expansions) if (lower.contains(e)) score += 1;
        boolean decisionSentence = isDecisionSentence(lower);
        if (patterns.isEmpty()) {
            // "What did we decide last week?": no topic words, so decisions are the answer.
            return decisionIntent && decisionSentence ? 1 : 0;
        }
        if (score == 0) return 0;
        if (decisionSentence && (decisionIntent || decisionChunk)) score += 2;
        return score;
    }

    private static boolean isDecisionSentence(String lower) {
        for (String cue : DECISION_CUES) if (lower.contains(cue)) return true;
        return false;
    }

    /** One line per sentence, without checklist or bullet markers from the source. */
    static String clean(String sentence) {
        return sentence.strip()
                .replaceFirst("^(?:[-*+]\\s*)?\\[[ xX]\\]\\s*", "")
                .replaceFirst("^[-*+]\\s+", "")
                .replaceAll("\\s+", " ");
    }
}
