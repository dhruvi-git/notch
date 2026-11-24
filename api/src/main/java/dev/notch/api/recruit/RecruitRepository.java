package dev.notch.api.recruit;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Hiring drives, their roles and requirements, and assignment runs. Drives are always scoped to their recruiter. */
@Repository
public class RecruitRepository {

    public record Drive(UUID id, String title, String description, Instant createdAt) {
    }

    public record Requirement(int skillId, double weight, boolean mustHave, String minLevel) {
    }

    public record Role(UUID id, String name, int seats, double threshold, List<Requirement> requirements) {
    }

    /** A seat-assignment run. The comparison fields are null for runs stored before best fit existed. */
    public record Run(UUID id, int totalSeats, int greedyFilled, int augmentingPaths, double millis, Instant runAt,
                      Double greedyScore, Integer maxFlowFilled, Double maxFlowScore, Double bestFitScore,
                      Double bestFitMillis) {
    }

    public record Placement(UUID roleId, UUID userId, String handle, double score, boolean moved) {
    }

    private static final RowMapper<Drive> DRIVE = (rs, n) -> new Drive(Db.uuid(rs, "id"), rs.getString("title"),
            rs.getString("description"), Db.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public RecruitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertDrive(UUID id, UUID recruiterId, String title, String description) {
        jdbc.sql("INSERT INTO drives (id, recruiter_id, title, description) VALUES (:id, :r, :t, :d)")
                .param("id", id).param("r", recruiterId).param("t", title).param("d", description)
                .update();
    }

    public Optional<Drive> drive(UUID recruiterId, UUID driveId) {
        return jdbc.sql("SELECT id, title, description, created_at FROM drives WHERE id = :id AND recruiter_id = :r")
                .param("id", driveId).param("r", recruiterId).query(DRIVE).optional();
    }

    public List<Drive> drives(UUID recruiterId) {
        return jdbc.sql("SELECT id, title, description, created_at FROM drives WHERE recruiter_id = :r ORDER BY created_at DESC")
                .param("r", recruiterId).query(DRIVE).list();
    }

    public boolean deleteDrive(UUID recruiterId, UUID driveId) {
        return jdbc.sql("DELETE FROM drives WHERE id = :id AND recruiter_id = :r")
                .param("id", driveId).param("r", recruiterId).update() == 1;
    }

    /** Roles in their saved order, each with its requirements. The caller checks the drive belongs to the recruiter. */
    public List<Role> roles(UUID driveId) {
        record ReqRow(UUID roleId, Requirement req) {
        }
        Map<UUID, List<Requirement>> reqs = new HashMap<>();
        jdbc.sql("""
                SELECT rr.role_id, rr.skill_id, rr.weight, rr.must_have, rr.min_level
                FROM role_requirements rr JOIN roles r ON r.id = rr.role_id
                WHERE r.drive_id = :d ORDER BY rr.weight DESC, rr.skill_id""")
                .param("d", driveId)
                .query((rs, n) -> new ReqRow(Db.uuid(rs, "role_id"), new Requirement(rs.getInt("skill_id"),
                        rs.getDouble("weight"), rs.getBoolean("must_have"), rs.getString("min_level"))))
                .list()
                .forEach(r -> reqs.computeIfAbsent(r.roleId(), k -> new ArrayList<>()).add(r.req()));
        return jdbc.sql("SELECT id, name, seats, threshold FROM roles WHERE drive_id = :d ORDER BY position")
                .param("d", driveId)
                .query((rs, n) -> {
                    UUID id = Db.uuid(rs, "id");
                    return new Role(id, rs.getString("name"), rs.getInt("seats"), rs.getDouble("threshold"),
                            List.copyOf(reqs.getOrDefault(id, List.of())));
                })
                .list();
    }

    /** Replaces every role of the drive. Earlier assignment runs go with them. */
    public void replaceRoles(UUID driveId, List<Role> roles) {
        jdbc.sql("DELETE FROM assignment_runs WHERE drive_id = :d").param("d", driveId).update();
        jdbc.sql("DELETE FROM roles WHERE drive_id = :d").param("d", driveId).update();
        int position = 0;
        for (Role r : roles) {
            jdbc.sql("INSERT INTO roles (id, drive_id, name, seats, threshold, position) VALUES (:id, :d, :n, :s, :t, :p)")
                    .param("id", r.id()).param("d", driveId).param("n", r.name()).param("s", r.seats())
                    .param("t", r.threshold()).param("p", position++)
                    .update();
            for (Requirement q : r.requirements()) {
                jdbc.sql("""
                        INSERT INTO role_requirements (role_id, skill_id, weight, must_have, min_level)
                        VALUES (:r, :s, :w, :m, :l)""")
                        .param("r", r.id()).param("s", q.skillId()).param("w", q.weight()).param("m", q.mustHave())
                        .param("l", q.minLevel())
                        .update();
            }
        }
    }

    public void insertRun(Run run, UUID driveId, List<Placement> placements) {
        jdbc.sql("""
                INSERT INTO assignment_runs (id, drive_id, total_seats, greedy_filled, augmenting_paths, millis, run_at,
                                             greedy_score, max_flow_filled, max_flow_score, best_fit_score, best_fit_millis)
                VALUES (:id, :d, :seats, :greedy, :paths, :ms, :at, :gs, :mff, :mfs, :bfs, :bfm)""")
                .param("id", run.id()).param("d", driveId).param("seats", run.totalSeats())
                .param("greedy", run.greedyFilled()).param("paths", run.augmentingPaths()).param("ms", run.millis())
                .param("at", Db.ts(run.runAt()))
                .param("gs", run.greedyScore()).param("mff", run.maxFlowFilled()).param("mfs", run.maxFlowScore())
                .param("bfs", run.bestFitScore()).param("bfm", run.bestFitMillis())
                .update();
        for (Placement p : placements) {
            jdbc.sql("INSERT INTO assignments (run_id, role_id, user_id, score, moved) VALUES (:run, :role, :u, :s, :m)")
                    .param("run", run.id()).param("role", p.roleId()).param("u", p.userId()).param("s", p.score())
                    .param("m", p.moved())
                    .update();
        }
    }

    public Optional<Run> latestRun(UUID driveId) {
        return jdbc.sql("""
                SELECT id, total_seats, greedy_filled, augmenting_paths, millis, run_at,
                       greedy_score, max_flow_filled, max_flow_score, best_fit_score, best_fit_millis
                FROM assignment_runs WHERE drive_id = :d ORDER BY run_at DESC LIMIT 1""")
                .param("d", driveId)
                .query((rs, n) -> new Run(Db.uuid(rs, "id"), rs.getInt("total_seats"), rs.getInt("greedy_filled"),
                        rs.getInt("augmenting_paths"), rs.getDouble("millis"), Db.instant(rs, "run_at"),
                        rs.getObject("greedy_score", Double.class), rs.getObject("max_flow_filled", Integer.class),
                        rs.getObject("max_flow_score", Double.class), rs.getObject("best_fit_score", Double.class),
                        rs.getObject("best_fit_millis", Double.class)))
                .optional();
    }

    public List<Placement> placements(UUID runId) {
        return jdbc.sql("""
                SELECT a.role_id, a.user_id, p.handle, a.score, a.moved
                FROM assignments a JOIN profiles p ON p.user_id = a.user_id
                WHERE a.run_id = :run ORDER BY a.score DESC, p.handle""")
                .param("run", runId)
                .query((rs, n) -> new Placement(Db.uuid(rs, "role_id"), Db.uuid(rs, "user_id"), rs.getString("handle"),
                        rs.getDouble("score"), rs.getBoolean("moved")))
                .list();
    }
}
