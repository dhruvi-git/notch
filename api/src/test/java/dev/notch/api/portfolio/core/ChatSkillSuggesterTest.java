package dev.notch.api.portfolio.core;

import dev.notch.api.ingest.core.ParsedConversation;
import dev.notch.api.ingest.core.ParsedMessage;
import dev.notch.api.portfolio.core.ChatSkillSuggester.Suggestion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatSkillSuggesterTest {

    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");

    private static ParsedConversation conv(String id, String... userThenAssistant) {
        List<ParsedMessage> msgs = new ArrayList<>();
        for (int i = 0; i < userThenAssistant.length; i++) {
            msgs.add(new ParsedMessage(id + "-" + i, i % 2 == 0 ? "user" : "assistant", userThenAssistant[i], T.plusSeconds(i)));
        }
        return new ParsedConversation(id, "chat " + id, T, msgs);
    }

    @Test
    void suggestsSkillsTheCandidateRaisedInSeveralConversations() {
        List<Suggestion> s = ChatSkillSuggester.suggest(List.of(
                conv("1", "How do I group by month in SQL?", "Use date_trunc in PostgreSQL."),
                conv("2", "My SQL join returns duplicates", "Check the keys."),
                conv("3", "pandas merge vs SQL join?", "Both work.")), SkillCatalogTest.CATALOG);
        assertEquals(1, s.size()); // SQL in 3 conversations; pandas once; PostgreSQL only from the assistant
        assertEquals("SQL", s.get(0).skill().name());
        assertEquals(3, s.get(0).conversations());
        assertEquals(0.6 * 0.3 + 0.4 * 3 / 30.0, s.get(0).depth(), 1e-3);
        assertEquals(T.plusSeconds(0), s.get(0).lastMentioned());
    }

    @Test
    void depthIsCappedAtOne() {
        List<ParsedConversation> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(conv("c" + i, "python question", "answer", "more python", "ok"));
        Suggestion py = ChatSkillSuggester.suggest(many, SkillCatalogTest.CATALOG).get(0);
        assertEquals(1.0, py.depth(), 1e-9);
        assertEquals(80, py.messages());
    }

    @Test
    void edgeCaseEmptyExportGivesNoSuggestions() {
        assertEquals(List.of(), ChatSkillSuggester.suggest(List.of(), SkillCatalogTest.CATALOG));
        assertEquals(List.of(), ChatSkillSuggester.suggest(List.of(conv("1", "hello there", "hi")), SkillCatalogTest.CATALOG));
    }

    @Test
    void repeatedMentionsInOneConversationDoNotQualify() {
        assertEquals(List.of(), ChatSkillSuggester.suggest(List.of(conv("1", "C++", "a", "C++ again", "b", "and C++")),
                SkillCatalogTest.CATALOG));
    }

    @Test
    void messagesWithoutTimestampsUseTheConversationStart() {
        ParsedConversation a = new ParsedConversation("a", "a", T, List.of(new ParsedMessage("a1", "user", "React hooks?", null)));
        ParsedConversation b = new ParsedConversation("b", "b", T.plusSeconds(99), List.of(new ParsedMessage("b1", "user", "React state", null)));
        List<Suggestion> s = ChatSkillSuggester.suggest(List.of(a, b), SkillCatalogTest.CATALOG);
        assertEquals(List.of(), s); // React is not in the test catalogue
        ParsedConversation c = new ParsedConversation("c", "c", T, List.of(new ParsedMessage("c1", "user", "Node.js streams", null)));
        ParsedConversation d = new ParsedConversation("d", "d", T.plusSeconds(99), List.of(new ParsedMessage("d1", "user", "nodejs cluster", null)));
        Suggestion node = ChatSkillSuggester.suggest(List.of(c, d), SkillCatalogTest.CATALOG).get(0);
        assertEquals("Node.js", node.skill().name());
        assertEquals(T.plusSeconds(99), node.lastMentioned());
    }
}
