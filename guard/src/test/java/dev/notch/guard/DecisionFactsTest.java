package dev.notch.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecisionFactsTest {

    private static List<String> names(List<TechCatalog.Tech> ts) {
        return ts.stream().map(TechCatalog.Tech::name).toList();
    }

    @Test
    void readsChoiceAndRejection() {
        DecisionFacts f = DecisionFacts.of("Let's go with Postgres instead of MongoDB because we need joins.", List.of("MongoDB"));
        assertEquals(List.of("PostgreSQL"), names(f.chosen()));
        assertEquals(List.of("MongoDB"), names(f.rejected()));
        assertEquals("database", f.category());
    }

    @Test
    void wordsAfterBecauseAreNotRejected() {
        DecisionFacts f = DecisionFacts.of("We chose Postgres instead of Mongo because Postgres has joins.", List.of());
        assertEquals(List.of("PostgreSQL"), names(f.chosen()));
        assertEquals(List.of("MongoDB"), names(f.rejected()));
    }

    @Test
    void aliasesMapToOneTool() {
        assertEquals(List.of("MongoDB"), names(TechCatalog.find("npm install mongoose to talk to Mongo")));
        assertEquals(List.of("Server-side sessions", "JWT"),
                names(TechCatalog.find("server-side sessions instead of JWTs")));
    }

    @Test
    void decisionWithoutKnownToolsHasNoCategory() {  // boundary
        DecisionFacts f = DecisionFacts.of("We decided to ship on Fridays.", List.of());
        assertNull(f.category());
        assertTrue(f.chosen().isEmpty());
        assertTrue(DecisionFacts.of(null, null).rejected().isEmpty());
    }

    @Test
    void aliasesMustBeWholeWords() {
        assertTrue(TechCatalog.find("the pgadmin page and reacting quickly").isEmpty());
    }
}
