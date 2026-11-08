package dev.notch.engine.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded generator of developer-conversation-like text: ordinary words mixed with
 * identifiers, file paths and error codes, which is what exact search must find.
 */
final class Synthetic {

    private Synthetic() {
    }

    private static final String[] WORDS = {
            "we", "should", "use", "the", "cache", "because", "latency", "is", "too", "high", "on",
            "login", "decided", "to", "move", "from", "mongo", "postgres", "migration", "failed",
            "after", "deploy", "retry", "with", "backoff", "token", "expired", "refresh", "flow",
            "fixed", "by", "adding", "index", "query", "slow", "pagination", "cursor", "offset",
            "rate", "limit", "per", "user", "queue", "worker", "timeout", "config", "env", "build",
            "test", "flaky", "mock", "service", "auth", "session", "cookie", "header", "cors"};

    private static final String[] IDENTIFIERS = {
            "useEffect", "useState", "AuthService", "UserRepository", "fetchUser", "refreshToken",
            "RateLimiter", "createSession", "handleLogin", "PaymentController", "queryBuilder",
            "redisClient", "parseConfig", "retryWithBackoff", "SearchIndex", "migrate_v12"};

    private static final String[] ERRORS = {
            "ECONNREFUSED", "ETIMEDOUT", "TypeError", "NullPointerException", "ERR_MODULE_NOT_FOUND",
            "403 Forbidden", "500 Internal Server Error", "OOMKilled", "SIGTERM", "CORS policy",
            "deadlock detected", "relation \"users\" does not exist"};

    private static final String[] DIRS = {
            "src", "app", "lib", "api", "web", "server", "client", "components", "hooks", "services",
            "auth", "billing", "search", "utils", "config", "db", "migrations", "tests", "pages"};

    private static final String[] EXT = {".ts", ".tsx", ".java", ".py", ".sql", ".md", ".json"};

    static List<String> chunks(Random rnd, int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder(240);
            int words = 25 + rnd.nextInt(20);
            for (int w = 0; w < words; w++) {
                if (w > 0) sb.append(' ');
                int r = rnd.nextInt(100);
                if (r < 6) sb.append(IDENTIFIERS[rnd.nextInt(IDENTIFIERS.length)]);
                else if (r < 9) sb.append(ERRORS[rnd.nextInt(ERRORS.length)]);
                else if (r < 11) sb.append(path(rnd));
                else if (r < 12) sb.append(rnd.nextInt(65_536));  // ports, ids, line numbers
                else sb.append(WORDS[rnd.nextInt(WORDS.length)]);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** Mostly realistic queries, some deliberately absent. */
    static List<String> queries(Random rnd, int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int r = rnd.nextInt(10);
            if (r < 4) out.add(ERRORS[rnd.nextInt(ERRORS.length)]);
            else if (r < 7) out.add(IDENTIFIERS[rnd.nextInt(IDENTIFIERS.length)]);
            else if (r < 9) out.add(WORDS[rnd.nextInt(WORDS.length)] + " " + WORDS[rnd.nextInt(WORDS.length)]);
            else out.add("zzq" + rnd.nextInt(1000));  // never present
        }
        return out;
    }

    static String path(Random rnd) {
        StringBuilder sb = new StringBuilder();
        int depth = 2 + rnd.nextInt(3);
        for (int d = 0; d < depth; d++) sb.append(DIRS[rnd.nextInt(DIRS.length)]).append('/');
        sb.append(IDENTIFIERS[rnd.nextInt(IDENTIFIERS.length)]);
        if (rnd.nextInt(4) == 0) sb.append(rnd.nextInt(100));
        return sb.append(EXT[rnd.nextInt(EXT.length)]).toString();
    }
}
