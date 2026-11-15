package dev.notch.engine.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Finds and redacts credentials before text is stored or sent to an LLM.
 *
 * <p>Two passes:
 * <ol>
 *   <li>Known key formats are located with {@link Kmp} on fixed prefixes, then extended to
 *       the end of the token and checked against a minimum length.</li>
 *   <li>Remaining long tokens are flagged when their Shannon entropy is high enough to look
 *       random (generic API keys, signed tokens).</li>
 * </ol>
 *
 * <p>This is a screen, not a guarantee: a secret in an unknown format with low entropy can
 * slip through, which the privacy page states.
 */
public final class SecretScanner {

    /** A redacted span of the original text, {@code [start, end)}. */
    public record Finding(String type, int start, int end) {
    }

    public record Result(String redacted, List<Finding> findings) {
    }

    private record Rule(String type, String prefix, int minLength) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("anthropic-key", "sk-ant-", 30),
            new Rule("openai-key", "sk-", 24),
            new Rule("aws-access-key", "AKIA", 20),
            new Rule("github-token", "ghp_", 30),
            new Rule("github-token", "github_pat_", 40),
            new Rule("slack-token", "xoxb-", 20),
            new Rule("slack-token", "xoxp-", 20));

    private static final String PEM_BEGIN = "-----BEGIN ";
    private static final String PEM_END = "-----END ";
    private static final String PEM_DASHES = "-----";

    static final int ENTROPY_MIN_LENGTH = 32;
    static final double ENTROPY_THRESHOLD = 4.2;  // bits per character

    public Result scan(String text) {
        Objects.requireNonNull(text, "text");
        List<Finding> found = new ArrayList<>();
        findPrivateKeys(text, found);
        for (Rule rule : RULES) findPrefixed(text, rule, found);
        findHighEntropy(text, found);
        List<Finding> merged = removeOverlaps(found);
        return new Result(redact(text, merged), merged);
    }

    private static void findPrivateKeys(String text, List<Finding> out) {
        for (int begin : Kmp.findAll(text, PEM_BEGIN)) {
            int lineEnd = text.indexOf('\n', begin);
            String header = text.substring(begin, lineEnd < 0 ? text.length() : lineEnd);
            if (!header.contains("PRIVATE KEY")) continue;
            int endMarker = Kmp.indexOf(text, PEM_END, begin);
            int end;
            if (endMarker < 0) {
                end = text.length();  // truncated key: redact to the end
            } else {
                int closing = Kmp.indexOf(text, PEM_DASHES, endMarker + PEM_END.length());
                end = closing < 0 ? text.length() : closing + PEM_DASHES.length();
            }
            out.add(new Finding("private-key", begin, end));
        }
    }

    private static void findPrefixed(String text, Rule rule, List<Finding> out) {
        for (int start : Kmp.findAll(text, rule.prefix())) {
            if (start > 0 && isTokenChar(text.charAt(start - 1))) continue;  // inside a word
            int end = start + rule.prefix().length();
            while (end < text.length() && isTokenChar(text.charAt(end))) end++;
            if (end - start >= rule.minLength()) out.add(new Finding(rule.type(), start, end));
        }
    }

    private static final java.util.regex.Pattern URL = java.util.regex.Pattern.compile("\\bhttps?://[^\\s\"'<>()\\]]+");

    /**
     * The path part of every URL (scheme, host and path, up to any '?'). Long random-looking
     * segments there are usually ids (a session link, a commit), not secrets; the query string,
     * where tokens do get passed, is still checked, and known key formats are caught anywhere.
     */
    private static List<int[]> urlPaths(String text) {
        List<int[]> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = URL.matcher(text);
        while (m.find()) {
            int q = text.indexOf('?', m.start());
            out.add(new int[] {m.start(), q >= 0 && q < m.end() ? q : m.end()});
        }
        return out;
    }

    private static void findHighEntropy(String text, List<Finding> out) {
        List<int[]> paths = urlPaths(text);
        int i = 0;
        while (i < text.length()) {
            if (!isTokenChar(text.charAt(i)) && !isBase64Extra(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && (isTokenChar(text.charAt(i)) || isBase64Extra(text.charAt(i)))) i++;
            // "NAME=value" (an env file, a shell export) is a name and a value, not one token: judge
            // only the value, so ANTHROPIC_MODEL=claude-haiku-5-5 isn't a "secret" and a real one is
            // redacted without hiding which variable it was.
            int eq = text.indexOf('=', start);
            if (eq > start && eq < i - 1 && text.charAt(eq + 1) != '='
                    && text.substring(start, eq).matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                start = eq + 1;
            }
            String token = text.substring(start, i);
            boolean inUrlPath = false;
            for (int[] p : paths) inUrlPath |= start >= p[0] && start < p[1];
            if (!inUrlPath && token.length() >= ENTROPY_MIN_LENGTH && hasMixedClasses(token)
                    && shannonEntropy(token) >= ENTROPY_THRESHOLD && !overlapsAny(out, start, i)) {
                out.add(new Finding("high-entropy-string", start, i));
            }
        }
    }

    /** Known formats win over the generic entropy rule. */
    private static boolean overlapsAny(List<Finding> found, int start, int end) {
        for (Finding f : found) {
            if (f.start() < end && start < f.end()) return true;
        }
        return false;
    }

    /** Shannon entropy of the character distribution, in bits per character. */
    static double shannonEntropy(String s) {
        if (s.isEmpty()) return 0;
        java.util.Map<Character, Integer> freq = new java.util.HashMap<>();
        for (int i = 0; i < s.length(); i++) freq.merge(s.charAt(i), 1, Integer::sum);
        double h = 0;
        for (int f : freq.values()) {
            double p = (double) f / s.length();
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    private static boolean hasMixedClasses(String s) {
        boolean digit = false, upper = false, lower = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) digit = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isLowerCase(c)) lower = true;
        }
        return digit && upper && lower;
    }

    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-';
    }

    private static boolean isBase64Extra(char c) {
        return c == '+' || c == '/' || c == '=';
    }

    /** Keeps the earliest, then longest, span wherever findings overlap. */
    private static List<Finding> removeOverlaps(List<Finding> found) {
        List<Finding> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt(Finding::start)
                .thenComparing(Comparator.comparingInt(Finding::end).reversed()));
        List<Finding> out = new ArrayList<>();
        int coveredUntil = -1;
        for (Finding f : sorted) {
            if (f.start() >= coveredUntil) {
                out.add(f);
                coveredUntil = f.end();
            }
        }
        return out;
    }

    private static String redact(String text, List<Finding> findings) {
        if (findings.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        for (Finding f : findings) {
            sb.append(text, last, f.start()).append("[REDACTED:").append(f.type()).append(']');
            last = f.end();
        }
        return sb.append(text, last, text.length()).toString();
    }
}
