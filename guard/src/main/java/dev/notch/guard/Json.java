package dev.notch.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer with no dependencies, shared by the CLI (one jar, nothing else)
 * and the API's model clients.
 * Objects become {@link LinkedHashMap}, arrays {@link ArrayList}, numbers {@link Double}.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("unexpected text after the value");
        return v;
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end of input");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || Character.isDigit(c)) yield number();
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (!peek('"')) throw error("expected a key");
            String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value());
            ws();
            if (peek(',')) {
                i++;
            } else {
                expect('}');
                return m;
            }
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            if (peek(',')) {
                i++;
            } else {
                expect(']');
                return a;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) throw error("unterminated escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("bad unicode escape");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw error("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = i;
        if (peek('-')) i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("expected " + word);
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
        i++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Invalid JSON at position " + i + ": " + what);
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, -1, 0);
        return sb.toString();
    }

    /** Indented output, for files people read (settings.json). */
    public static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 2, 0);
        return sb.append('\n').toString();
    }

    private static void write(StringBuilder sb, Object v, int indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            quote(sb, str);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            int k = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (k++ > 0) sb.append(',');
                newline(sb, indent, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent >= 0 ? ": " : ":");
                write(sb, e.getValue(), indent, depth + 1);
            }
            if (k > 0) newline(sb, indent, depth);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            int k = 0;
            for (Object o : l) {
                if (k++ > 0) sb.append(',');
                newline(sb, indent, depth + 1);
                write(sb, o, indent, depth + 1);
            }
            if (k > 0) newline(sb, indent, depth);
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent < 0) return;
        sb.append('\n');
        sb.append(" ".repeat(indent * depth));
    }

    private static void quote(StringBuilder sb, String str) {
        sb.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** Typed access helpers for parsed values. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("expected a JSON object");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        if (v instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException("expected a JSON array");
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
