package dev.notch.api.search.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a search query asks for: exact terms, quoted phrases, whether it is about decisions,
 * a time window from phrases like "last week", and soft expansions of broad words.
 *
 * <p>Ordinary words are cut to a stem ("caching" → "cach") instead of being matched whole.
 * Because the suffix array finds substrings, the stem matches "cache", "cached" and
 * "caching" alike. Identifiers, paths and error codes are kept exactly as typed.
 *
 * <p>Broad words are expanded to the tools they usually mean ("database" also looks for
 * "postgres", "mysql", ...). Expansions count for less than typed terms. Real embeddings
 * cover this gap better; the expansion keeps offline and private search useful.
 */
public record QueryTerms(List<String> terms, List<String> phrases, List<String> expansions,
                         boolean decisionIntent, Instant since) {

    /** Category word (stemmed) to the names developers actually write. */
    static final java.util.Map<String, List<String>> EXPANSIONS = java.util.Map.ofEntries(
            java.util.Map.entry("database", List.of("postgre", "mysql", "mongo", "sqlite", "dynamo", "supabase", "neon")),
            java.util.Map.entry("databas", List.of("postgre", "mysql", "mongo", "sqlite", "dynamo", "supabase", "neon")),
            java.util.Map.entry("cach", List.of("redis", "memcache", "cdn")),
            java.util.Map.entry("auth", List.of("jwt", "session", "oauth", "login", "cookie")),
            java.util.Map.entry("authentication", List.of("jwt", "session", "oauth", "login", "cookie")),
            java.util.Map.entry("queue", List.of("kafka", "rabbitmq", "sqs", "worker")),
            java.util.Map.entry("deploy", List.of("vercel", "docker", "kubernetes", "cloud run", "fly.io")),
            java.util.Map.entry("frontend", List.of("react", "next.js", "vue", "svelte", "tailwind")),
            java.util.Map.entry("payment", List.of("stripe", "invoice", "billing")),
            java.util.Map.entry("hosting", List.of("vercel", "netlify", "cloud run", "aws", "gcp")));

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "by",
            "from", "about", "into", "over", "is", "are", "was", "were", "be", "been", "being", "do",
            "does", "did", "done", "have", "has", "had", "it", "its", "this", "that", "these", "those",
            "we", "us", "our", "i", "me", "my", "you", "your", "they", "them", "what", "which", "who",
            "whom", "when", "where", "why", "how", "should", "would", "could", "can", "will", "shall",
            "any", "some", "there", "here", "then", "than", "so", "if", "not", "no", "yes", "up",
            "out", "all", "use", "used", "using", "get", "got", "tell", "show", "find", "please",
            "again", "still", "just", "also", "very", "really", "thing", "things", "stuff", "stop",
            "last", "week", "month", "today", "yesterday", "ago", "days", "time");

    private static final Set<String> DECISION_WORDS = Set.of(
            "decide", "decided", "decides", "deciding", "decision", "decisions", "chose", "choose",
            "chosen", "choice", "choices", "pick", "picked", "settle", "settled", "agree", "agreed");

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{2,100})\"");
    private static final Pattern LAST_N_DAYS = Pattern.compile("\\b(?:last|past)\\s+(\\d{1,3})\\s+days?\\b");
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}_./:#+-]+");
    private static final String[] SUFFIXES = {"ations", "ation", "ings", "ing", "ies", "ed", "es", "s"};

    public QueryTerms {
        terms = List.copyOf(terms);
        phrases = List.copyOf(phrases);
        expansions = List.copyOf(expansions);
    }

    public boolean isEmpty() {
        return terms.isEmpty() && phrases.isEmpty();
    }

    /** Every exact-match pattern: phrases first, then terms. */
    public List<String> patterns() {
        List<String> all = new ArrayList<>(phrases);
        all.addAll(terms);
        return all;
    }

    public static QueryTerms parse(String query, Instant now) {
        String q = query.strip();
        List<String> phrases = new ArrayList<>();
        Matcher quoted = QUOTED.matcher(q);
        while (quoted.find()) phrases.add(quoted.group(1).strip().toLowerCase(Locale.ROOT));
        q = QUOTED.matcher(q).replaceAll(" ");

        String lower = q.toLowerCase(Locale.ROOT);
        Instant since = timeWindow(lower, now);
        boolean decisionIntent = false;
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : SPLIT.split(q)) {
            String token = raw.replaceAll("^[.:/#+-]+|[.:/#+-]+$", "");
            if (token.isEmpty()) continue;
            String low = token.toLowerCase(Locale.ROOT);
            if (DECISION_WORDS.contains(low)) {
                decisionIntent = true;
                continue;
            }
            if (STOPWORDS.contains(low) || low.chars().allMatch(Character::isDigit) && low.length() < 3) continue;
            if (isIdentifier(token)) {
                terms.add(low);
            } else if (low.length() >= 3) {
                terms.add(stem(low));
            }
        }
        Set<String> expanded = new LinkedHashSet<>();
        for (String t : terms) {
            for (String e : EXPANSIONS.getOrDefault(t, List.of())) {
                if (!terms.contains(e)) expanded.add(e);
            }
        }
        return new QueryTerms(new ArrayList<>(terms), phrases, new ArrayList<>(expanded), decisionIntent, since);
    }

    /** Tokens with digits, separators or inner capitals are code: keep them whole. */
    static boolean isIdentifier(String token) {
        if (token.length() < 2) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isDigit(c) || c == '_' || c == '.' || c == '/' || c == ':' || c == '#') return true;
            if (i > 0 && Character.isUpperCase(c)) return true;
        }
        return false;
    }

    static String stem(String word) {
        if (word.length() < 6) return word;
        for (String suffix : SUFFIXES) {
            if (!word.endsWith(suffix) || word.length() - suffix.length() < 4) continue;
            String base = word.substring(0, word.length() - suffix.length());
            // "-es" is a suffix only after s, x, z, ch, sh ("caches"); "postgres" just loses "s".
            if (suffix.equals("es") && !(base.endsWith("s") || base.endsWith("x") || base.endsWith("z")
                    || base.endsWith("ch") || base.endsWith("sh"))) {
                continue;
            }
            return base;
        }
        return word;
    }

    static Instant timeWindow(String lower, Instant now) {
        Matcher n = LAST_N_DAYS.matcher(lower);
        if (n.find()) return now.minus(Duration.ofDays(Math.max(1, Integer.parseInt(n.group(1)))));
        if (lower.contains("yesterday")) return now.minus(Duration.ofDays(2));
        if (lower.contains("today")) return now.minus(Duration.ofDays(1));
        if (lower.contains("last week")) return now.minus(Duration.ofDays(14));
        if (lower.contains("this week") || lower.contains("past week")) return now.minus(Duration.ofDays(7));
        if (lower.contains("last month")) return now.minus(Duration.ofDays(62));
        if (lower.contains("this month") || lower.contains("past month")) return now.minus(Duration.ofDays(31));
        return null;
    }
}
