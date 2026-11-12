package dev.notch.api.jobs;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * A job queue in Postgres. Workers claim jobs with {@code FOR UPDATE SKIP LOCKED}, so several
 * API instances can poll the same table without taking the same job twice.
 */
@Repository
public class JobRepository {

    private final JdbcClient jdbc;

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(String kind, UUID projectId, UUID importId) {
        jdbc.sql("INSERT INTO jobs (kind, project_id, import_id) VALUES (:kind, :p, :i)")
                .param("kind", kind)
                .param("p", projectId)
                .param("i", importId)
                .update();
    }

    /** Whether a job of this kind is already waiting or running for the project. */
    public boolean hasOpen(String kind, UUID projectId) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE kind = :k AND project_id = :p AND status IN ('PENDING', 'RUNNING')")
                .param("k", kind)
                .param("p", projectId)
                .query(Long.class)
                .single() > 0;
    }

    public Optional<Job> claimNext() {
        return jdbc.sql("""
                UPDATE jobs SET status = 'RUNNING', started_at = now(), attempts = attempts + 1
                WHERE id = (
                    SELECT id FROM jobs
                    WHERE status = 'PENDING' AND run_after <= now()
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1)
                RETURNING id, kind, project_id, import_id, attempts""")
                .query((rs, n) -> new Job(rs.getLong("id"), rs.getString("kind"),
                        Db.uuid(rs, "project_id"), Db.uuid(rs, "import_id"), rs.getInt("attempts")))
                .optional();
    }

    public void complete(long id) {
        jdbc.sql("UPDATE jobs SET status = 'DONE', finished_at = now(), last_error = NULL WHERE id = :id")
                .param("id", id)
                .update();
    }

    /** Puts the job back in the queue after an exponential delay. */
    public void retryLater(long id, int attempts, String error) {
        long delaySeconds = (long) Math.min(600, 5 * Math.pow(4, attempts - 1));  // 5s, 20s, 80s ...
        jdbc.sql("""
                UPDATE jobs SET status = 'PENDING', last_error = :e,
                    run_after = now() + make_interval(secs => :delay)
                WHERE id = :id""")
                .param("e", error)
                .param("delay", delaySeconds)
                .param("id", id)
                .update();
    }

    public void fail(long id, String error) {
        jdbc.sql("UPDATE jobs SET status = 'FAILED', finished_at = now(), last_error = :e WHERE id = :id")
                .param("e", error)
                .param("id", id)
                .update();
    }

    /** Jobs left RUNNING by a crashed instance go back to the queue after ten minutes. */
    public int requeueStale() {
        return jdbc.sql("""
                UPDATE jobs SET status = 'PENDING'
                WHERE status = 'RUNNING' AND started_at < now() - interval '10 minutes'""")
                .update();
    }
}
