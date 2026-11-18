package dev.notch.api.portfolio.core;

import dev.notch.api.ingest.core.ParsedConversation;
import dev.notch.api.ingest.core.ParsedMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suggests skills from a ChatGPT or Claude export: which catalogue skills the candidate kept
 * coming back to, and how deeply.
 *
 * <p>Only the candidate's own messages are read, so a skill the assistant mentioned in passing
 * does not count. A skill is suggested when it appears in at least {@link #MIN_CONVERSATIONS}
 * separate conversations. Depth grows with the number of conversations (60%, full at 10) and
 * messages (40%, full at 30) that mention it. Chat history shows interest and learning, not
 * finished work, so in scoring a suggestion is worth at most a fifth of a strong repository, and
 * nothing counts until the candidate approves it.
 */
public final class ChatSkillSuggester {

    public record Suggestion(SkillCatalog.Skill skill, int conversations, int messages, double depth, Instant lastMentioned) {
    }

    public static final int MIN_CONVERSATIONS = 2;
    public static final int MAX_SUGGESTIONS = 15;
    static final int FULL_CONVERSATIONS = 10;
    static final int FULL_MESSAGES = 30;

    private ChatSkillSuggester() {
    }

    public static List<Suggestion> suggest(List<ParsedConversation> conversations, SkillCatalog catalog) {
        Map<Integer, Set<String>> convs = new HashMap<>();
        Map<Integer, Integer> msgs = new HashMap<>();
        Map<Integer, Instant> last = new HashMap<>();
        for (ParsedConversation c : conversations) {
            for (ParsedMessage m : c.messages()) {
                if (!"user".equals(m.role())) continue;
                Instant when = m.sentAt() != null ? m.sentAt() : c.startedAt();
                Set<Integer> inMessage = new HashSet<>();
                for (SkillCatalog.Skill s : catalog.extract(m.text())) inMessage.add(s.id());
                for (int id : inMessage) {
                    convs.computeIfAbsent(id, k -> new HashSet<>()).add(c.externalId());
                    msgs.merge(id, 1, Integer::sum);
                    if (when != null) last.merge(id, when, (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        }
        List<Suggestion> out = new ArrayList<>();
        for (Map.Entry<Integer, Set<String>> e : convs.entrySet()) {
            int c = e.getValue().size();
            if (c < MIN_CONVERSATIONS) continue;
            int m = msgs.get(e.getKey());
            double depth = 0.6 * Math.min(1.0, (double) c / FULL_CONVERSATIONS) + 0.4 * Math.min(1.0, (double) m / FULL_MESSAGES);
            out.add(new Suggestion(catalog.get(e.getKey()).orElseThrow(), c, m, Math.round(depth * 1000) / 1000.0,
                    last.get(e.getKey())));
        }
        out.sort(Comparator.comparingDouble(Suggestion::depth).reversed().thenComparing(s -> s.skill().name()));
        return out.size() > MAX_SUGGESTIONS ? List.copyOf(out.subList(0, MAX_SUGGESTIONS)) : List.copyOf(out);
    }
}
