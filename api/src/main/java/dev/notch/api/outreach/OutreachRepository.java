package dev.notch.api.outreach;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OutreachRepository {

    /** One message, with what each side needs to show it. */
    public record Row(UUID id, UUID driveId, String driveTitle, UUID roleId, String roleName, UUID recruiterId,
                      String recruiterHandle, UUID candidateId, String candidateHandle, String subject, String body,
                      String status, String delivery, Instant createdAt, Instant respondedAt) {
    }

    /** A user's handle (public profile first) and email, for addressing a message. */
    public record Contact(String handle, String email) {
    }

    private static final String SELECT = """
            SELECT o.id, o.drive_id, d.title AS drive_title, o.role_id, o.role_name, o.recruiter_id,
                   COALESCE(rp.handle, ru.handle) AS recruiter_handle, o.candidate_id,
                   COALESCE(cp.handle, cu.handle) AS candidate_handle, o.subject, o.body, o.status, o.delivery,
                   o.created_at, o.responded_at
            FROM outreach o
            JOIN drives d ON d.id = o.drive_id
            JOIN users ru ON ru.id = o.recruiter_id LEFT JOIN profiles rp ON rp.user_id = o.recruiter_id
            JOIN users cu ON cu.id = o.candidate_id LEFT JOIN profiles cp ON cp.user_id = o.candidate_id
            """;

    private final JdbcClient jdbc;

    public OutreachRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID driveId, UUID roleId, String roleName, UUID recruiterId, UUID candidateId,
                       String subject, String body, String delivery) {
        jdbc.sql("""
                INSERT INTO outreach (id, drive_id, role_id, role_name, recruiter_id, candidate_id, subject, body, delivery)
                VALUES (:id, :d, :r, :rn, :rec, :c, :s, :b, :del)""")
                .param("id", id).param("d", driveId).param("r", roleId).param("rn", roleName).param("rec", recruiterId)
                .param("c", candidateId).param("s", subject).param("b", body).param("del", delivery)
                .update();
    }

    public void setDelivery(UUID id, String delivery) {
        jdbc.sql("UPDATE outreach SET delivery = :d WHERE id = :id").param("d", delivery).param("id", id).update();
    }

    public boolean exists(UUID driveId, UUID candidateId) {
        return jdbc.sql("SELECT count(*) FROM outreach WHERE drive_id = :d AND candidate_id = :c")
                .param("d", driveId).param("c", candidateId).query(Integer.class).single() > 0;
    }

    public Optional<Row> get(UUID id) {
        return jdbc.sql(SELECT + " WHERE o.id = :id").param("id", id).query(this::row).optional();
    }

    public List<Row> forDrive(UUID driveId) {
        return jdbc.sql(SELECT + " WHERE o.drive_id = :d ORDER BY o.created_at DESC").param("d", driveId).query(this::row).list();
    }

    public List<Row> forCandidate(UUID candidateId) {
        return jdbc.sql(SELECT + " WHERE o.candidate_id = :c ORDER BY o.created_at DESC").param("c", candidateId)
                .query(this::row).list();
    }

    /** Records the candidate's answer; false when the message isn't theirs. */
    public boolean respond(UUID id, UUID candidateId, String status) {
        return jdbc.sql("UPDATE outreach SET status = :s, responded_at = now() WHERE id = :id AND candidate_id = :c")
                .param("s", status).param("id", id).param("c", candidateId).update() == 1;
    }

    public Optional<Contact> contact(UUID userId) {
        return jdbc.sql("""
                SELECT COALESCE(p.handle, u.handle) AS handle, u.email FROM users u
                LEFT JOIN profiles p ON p.user_id = u.id WHERE u.id = :id""")
                .param("id", userId)
                .query((rs, n) -> new Contact(rs.getString("handle"), rs.getString("email")))
                .optional();
    }

    private Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(Db.uuid(rs, "id"), Db.uuid(rs, "drive_id"), rs.getString("drive_title"), Db.uuid(rs, "role_id"),
                rs.getString("role_name"), Db.uuid(rs, "recruiter_id"), rs.getString("recruiter_handle"),
                Db.uuid(rs, "candidate_id"), rs.getString("candidate_handle"), rs.getString("subject"), rs.getString("body"),
                rs.getString("status"), rs.getString("delivery"), Db.instant(rs, "created_at"), Db.instant(rs, "responded_at"));
    }
}
