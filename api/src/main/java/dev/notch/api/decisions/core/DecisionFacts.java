package dev.notch.api.decisions.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a decision chose and what it ruled out, as known tools where possible.
 * "Let's go with Postgres instead of MongoDB because …" → chose PostgreSQL, rejected MongoDB.
 */
public record DecisionFacts(String chosenPhrase, List<TechCatalog.Tech> chosen, List<TechCatalog.Tech> rejected) {

    private static final String STOP = "(?:instead|rather|than|over|because|since|for|to|and|but|so|as|which|that|with|in|on|at|from|when|if)";
    private static final Pattern CHOICE = Pattern.compile(
            "\\b(?:go(?:ing)? with|we'?ll use|we will use|use|using|chose|choose|picked|pick|settled on|"
                    + "switch(?:ing)? to|mov(?:e|ing) to|migrat(?:e|ing) to|adopt(?:ing)?|stick with|keep)\\s+"
                    + "(?:(?:a|an|the|our)\\s+)?"
                    + "([A-Za-z0-9_.+#/-]+(?:\\s+(?!" + STOP + "\\b)[A-Za-z0-9_.+#/-]+){0,3})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REJECTION = Pattern.compile(
            "\\b(?:instead of|rather than|not|over|no longer|drop(?:ping)?|replac(?:e|ing))\\s+"
                    + "(?:(?:a|an|the|our)\\s+)?"
                    + "([A-Za-z0-9_.+#/-]+(?:\\s+(?!" + STOP + "\\b)[A-Za-z0-9_.+#/-]+){0,3})",
            Pattern.CASE_INSENSITIVE);

    public DecisionFacts {
        chosen = List.copyOf(chosen);
        rejected = List.copyOf(rejected);
    }

    /** The category of the main choice, or null when no known tool was chosen. */
    public String category() {
        return chosen.isEmpty() ? null : chosen.get(0).category();
    }

    public static DecisionFacts of(String decisionText, List<String> rejectedOptions) {
        String text = decisionText == null ? "" : decisionText;
        String chosenPhrase = null;
        List<TechCatalog.Tech> chosen = new ArrayList<>();
        Matcher m = CHOICE.matcher(text);
        if (m.find()) {
            chosenPhrase = m.group(1).strip();
            chosen.addAll(TechCatalog.find(chosenPhrase));
        }
        List<TechCatalog.Tech> rejected = new ArrayList<>();
        if (rejectedOptions != null) {
            for (String r : rejectedOptions) addAll(rejected, TechCatalog.find(r));
        }
        Matcher r = REJECTION.matcher(text);
        while (r.find()) addAll(rejected, TechCatalog.find(r.group(1)));
        if (chosen.isEmpty()) {
            // No explicit cue: any tool mentioned that was not rejected counts as the choice.
            for (TechCatalog.Tech t : TechCatalog.find(text)) if (!rejected.contains(t)) chosen.add(t);
        }
        chosen.removeIf(rejected::contains);
        return new DecisionFacts(chosenPhrase, chosen, rejected);
    }

    private static void addAll(List<TechCatalog.Tech> into, List<TechCatalog.Tech> more) {
        for (TechCatalog.Tech t : more) if (!into.contains(t)) into.add(t);
    }
}
