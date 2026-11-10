package dev.notch.cli;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void parsesNestedValues() {
        Map<String, Object> m = Json.obj(Json.parse("{\"a\": [1, 2.5, true, null, \"x\"], \"b\": {\"c\": \"\\u00e9\\n\"}}"));
        assertEquals(List.of(1.0, 2.5, true, "x"), Json.arr(m.get("a")).stream().filter(o -> o != null).toList());
        assertEquals("é\n", Json.obj(m.get("b")).get("c"));
    }

    @Test
    void roundTripsThroughWrite() {
        String json = "{\"id\":1,\"s\":\"quote \\\" and \\\\ and \\t\",\"list\":[],\"obj\":{}}";
        assertEquals(json, Json.write(Json.parse(json)));
    }

    @Test
    void prettyPrintsForPeople() {
        assertEquals("{\n  \"a\": [\n    1\n  ]\n}\n", Json.pretty(Json.parse("{\"a\":[1]}")));
    }

    @Test
    void rejectsMalformedInput() {  // invalid
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1, 2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} extra"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse(""));
    }

    @Test
    void controlCharactersAreEscaped() {  // boundary
        assertEquals("\"\\u0001\"", Json.write("\u0001"));
    }
}
