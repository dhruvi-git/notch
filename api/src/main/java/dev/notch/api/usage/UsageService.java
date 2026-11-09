package dev.notch.api.usage;

import dev.notch.api.config.ApiException;
import dev.notch.api.config.NotchProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Daily allowances for actions that cost money (LLM calls). Allowances reset at midnight UTC.
 *
 * <p>The increment is one atomic upsert: the row is only updated while {@code used < limit},
 * so two simultaneous requests can never both take the last unit.
 */
@Service
public class UsageService {

    public static final String IMPORT = "import";
    public static final String QUESTION = "question";

    private final JdbcClient jdbc;
    private final NotchProperties props;

    public UsageService(JdbcClient jdbc, NotchProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public int limit(String action) {
        return switch (action) {
            case IMPORT -> props.limits().importsPerDay();
            case QUESTION -> props.limits().questionsPerDay();
            default -> throw new IllegalArgumentException("unknown action " + action);
        };
    }

    /** Takes one unit, or throws 429 when today's allowance is used up. */
    public void consume(UUID userId, String action) {
        int limit = limit(action);
        boolean taken = limit > 0 && jdbc.sql("""
                INSERT INTO daily_usage (user_id, day, action, used)
                VALUES (:userId, (now() AT TIME ZONE 'UTC')::date, :action, 1)
                ON CONFLICT (user_id, day, action)
                DO UPDATE SET used = daily_usage.used + 1 WHERE daily_usage.used < :limit
                RETURNING used""")
                .param("userId", userId)
                .param("action", action)
                .param("limit", limit)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!taken) {
            String what = action.equals(IMPORT) ? "imports" : "questions";
            throw ApiException.limitReached("You've used today's " + limit + " " + what
                    + ". Search still works, and the allowance resets at midnight UTC.");
        }
    }

    /** Remaining units per action for today. */
    public Map<String, Integer> remaining(UUID userId) {
        Map<String, Integer> used = new java.util.HashMap<>();
        jdbc.sql("""
                SELECT action, used FROM daily_usage
                WHERE user_id = :userId AND day = (now() AT TIME ZONE 'UTC')::date""")
                .param("userId", userId)
                .query((rs, n) -> Map.entry(rs.getString("action"), rs.getInt("used")))
                .list()
                .forEach(e -> used.put(e.getKey(), e.getValue()));
        return Map.of(
                IMPORT, Math.max(0, limit(IMPORT) - used.getOrDefault(IMPORT, 0)),
                QUESTION, Math.max(0, limit(QUESTION) - used.getOrDefault(QUESTION, 0)));
    }
}
