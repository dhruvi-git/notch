package dev.notch.api.ownership.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Grades answers when no language model is available, and judges a finished check.
 *
 * <p>The offline grade looks for three things: the ideas a good answer needs (the question's
 * rubric), the author's own names from the code (an answer about this code, not a textbook one),
 * and enough substance. It is deliberately transparent: the feedback says which ideas were
 * covered and which were missed. A model grade, when available, replaces it.
 *
 * <p>Integrity signals are recorded, not punished silently: pasting into an answer, leaving the
 * page, and typing faster than a person can are shown to the recruiter next to the answer.
 */
public final class AnswerGrader {

    public record Grade(int score, List<String> covered, List<String> missed, String feedback) {
    }

    /**
     * @param score    the mean of the answer scores, 0 to 100
     * @param defended whether the check is passed: every question answered, a score of at least
     *                 {@link #PASS_SCORE}, and at most one pasted answer
     */
    public record Verdict(int score, boolean defended, int answered, int pasted, String summary) {
    }

    public static final int PASS_SCORE = 70;
    static final double W_IDEAS = 0.5, W_SPECIFIC = 0.3, W_DEPTH = 0.2;
    static final int FULL_DEPTH_WORDS = 70;
    static final int TOO_SHORT_WORDS = 8;
    static final int HUMAN_WPM = 120;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_']+");

    private AnswerGrader() {
    }

    public static Grade grade(List<List<String>> rubric, List<String> identifiers, String answer) {
        String a = answer == null ? "" : answer.strip();
        int words = words(a);
        if (words < TOO_SHORT_WORDS) {
            return new Grade(Math.min(10, words), List.of(), rubric.stream().map(g -> g.get(0)).toList(),
                    "Too short to show understanding. Explain the reasoning, using the names in the code.");
        }
        String lower = a.toLowerCase(Locale.ROOT);
        List<String> covered = new ArrayList<>(), missed = new ArrayList<>();
        for (List<String> group : rubric) {
            boolean hit = group.stream().anyMatch(t -> lower.contains(t.toLowerCase(Locale.ROOT)));
            (hit ? covered : missed).add(group.get(0));
        }
        double ideas = rubric.isEmpty() ? 1 : (double) covered.size() / rubric.size();
        long named = identifiers.stream().filter(id -> Pattern.compile("\\b" + Pattern.quote(id) + "\\b").matcher(a).find()).count();
        double specific = identifiers.isEmpty() ? 1 : Math.min(1.0, named / (double) Math.min(2, identifiers.size()));
        double depth = Math.min(1.0, (double) words / FULL_DEPTH_WORDS);
        int score = (int) Math.round(100 * (W_IDEAS * ideas + W_SPECIFIC * specific + W_DEPTH * depth));

        StringBuilder fb = new StringBuilder();
        if (!covered.isEmpty()) fb.append("Covered: ").append(String.join(", ", covered)).append(". ");
        if (!missed.isEmpty()) fb.append("Not addressed: ").append(String.join(", ", missed)).append(". ");
        if (named == 0 && !identifiers.isEmpty()) fb.append("Didn't refer to anything named in the code. ");
        return new Grade(score, List.copyOf(covered), List.copyOf(missed), fb.toString().strip());
    }

    /** Signals shown beside an answer. They inform the recruiter; they don't change the answer's score. */
    public static List<String> integrityFlags(String answer, long millis, int pastes, int focusLost) {
        List<String> flags = new ArrayList<>();
        if (pastes > 0) flags.add(pastes == 1 ? "Pasted text once" : "Pasted text " + pastes + " times");
        if (focusLost >= 2) flags.add("Left the page " + focusLost + " times");
        int words = words(answer == null ? "" : answer);
        if (words >= 40 && millis > 0 && words / (millis / 60_000.0) > HUMAN_WPM) {
            flags.add("Answered at over " + HUMAN_WPM + " words a minute");
        }
        return flags;
    }

    public static Verdict verdict(List<Integer> scores, int questions, int pasted) {
        int answered = scores.size();
        int mean = answered == 0 ? 0 : (int) Math.round(scores.stream().mapToInt(Integer::intValue).sum() / (double) questions);
        boolean defended = answered == questions && questions > 0 && mean >= PASS_SCORE && pasted <= 1;
        String summary;
        if (defended) summary = "Defended: explained their own code convincingly.";
        else if (answered < questions) summary = "Not defended: " + (questions - answered) + " question" + (questions - answered == 1 ? "" : "s") + " left unanswered.";
        else if (pasted > 1) summary = "Not defended: " + pasted + " answers were pasted in.";
        else summary = "Not defended: the answers scored " + mean + ", below " + PASS_SCORE + ".";
        return new Verdict(mean, defended, answered, pasted, summary);
    }

    static int words(String s) {
        int n = 0;
        var m = WORD.matcher(s);
        while (m.find()) n++;
        return n;
    }
}
