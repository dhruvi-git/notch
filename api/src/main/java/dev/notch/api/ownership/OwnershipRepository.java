package dev.notch.api.ownership;

import dev.notch.api.support.Db;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ownership Checks and their questions. A check is always read through its owner, or as a finished public transcript. */
@Repository
public class OwnershipRepository {

    public record CheckRow(UUID id, UUID userId, UUID evidenceId, String status, Instant startedAt, Instant deadline,
                           Instant finishedAt, Integer score, boolean defended, String summary, String questionsBy,
                           String gradedBy, int planElements, int planCovered) {
        public boolean open() {
            return "OPEN".equals(status);
        }
    }

    public record QuestionRow(UUID id, int position, String path, int startLine, int endLine, String language, String code,
                              String concept, String prompt, List<List<String>> rubric, List<String> identifiers,
                              List<String> covers, String answer, Instant answeredAt, Long millis, int pastes, int focusLost,
                              Integer score, String feedback, List<String> flags) {
    }

    /** A question to store when a check is created. */
    public record NewQuestion(int position, String path, int startLine, int endLine, String language, String code,
                              String concept, String prompt, List<List<String>> rubric, List<String> identifiers,
                              List<String> covers) {
    }

    /** One graded question, saved when the check finishes. */
    public record GradedQuestion(UUID id, int score, String feedback, List<String> flags) {
    }

    /** The public header of a finished check: whose it is and which repository. */
    public record PublicHeader(String handle, String repoTitle, String repoUrl) {
    }

    private static final String CHECK_COLUMNS = """
            c.id, c.user_id, c.evidence_id, c.status, c.started_at, c.deadline, c.finished_at, c.score, c.defended,
            c.summary, c.questions_by, c.graded_by, c.plan_elements, c.plan_covered""";

    private static final RowMapper<CheckRow> CHECK = (rs, n) -> new CheckRow(Db.uuid(rs, "id"), Db.uuid(rs, "user_id"),
            Db.uuid(rs, "evidence_id"), rs.getString("status"), Db.instant(rs, "started_at"), Db.instant(rs, "deadline"),
            Db.instant(rs, "finished_at"), (Integer) rs.getObject("score"), rs.getBoolean("defended"), rs.getString("summary"),
            rs.getString("questions_by"), rs.getString("graded_by"), rs.getInt("plan_elements"), rs.getInt("plan_covered"));

    private static final RowMapper<QuestionRow> QUESTION = (rs, n) -> new QuestionRow(Db.uuid(rs, "id"), rs.getInt("position"),
            rs.getString("path"), rs.getInt("start_line"), rs.getInt("end_line"), rs.getString("language"), rs.getString("code"),
            rs.getString("concept"), rs.getString("prompt"), rubric(rs.getString("rubric")), lines(rs.getString("identifiers")),
            lines(rs.getString("covers")), rs.getString("answer"), Db.instant(rs, "answered_at"), (Long) longOrNull(rs, "millis"),
            rs.getInt("pastes"), rs.getInt("focus_lost"), (Integer) rs.getObject("score"), rs.getString("feedback"),
            lines(rs.getString("flags")));

    private final JdbcClient jdbc;

    public OwnershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void create(UUID id, UUID userId, UUID evidenceId, Instant deadline, String questionsBy, int planElements,
                       int planCovered, List<NewQuestion> questions) {
        jdbc.sql("""
                INSERT INTO ownership_checks (id, user_id, evidence_id, status, deadline, questions_by, plan_elements, plan_covered)
                VALUES (:id, :u, :e, 'OPEN', :deadline, :by, :elements, :covered)""")
                .param("id", id).param("u", userId).param("e", evidenceId).param("deadline", Db.ts(deadline))
                .param("by", questionsBy).param("elements", planElements).param("covered", planCovered)
                .update();
        for (NewQuestion q : questions) {
            jdbc.sql("""
                    INSERT INTO ownership_questions (id, check_id, position, path, start_line, end_line, language, code,
                                                     concept, prompt, rubric, identifiers, covers)
                    VALUES (:id, :c, :pos, :path, :start, :end, :lang, :code, :concept, :prompt, :rubric, :ids, :covers)""")
                    .param("id", UUID.randomUUID()).param("c", id).param("pos", q.position()).param("path", q.path())
                    .param("start", q.startLine()).param("end", q.endLine()).param("lang", q.language()).param("code", q.code())
                    .param("concept", q.concept()).param("prompt", q.prompt()).param("rubric", joinRubric(q.rubric()))
                    .param("ids", String.join("\n", q.identifiers())).param("covers", String.join("\n", q.covers()))
                    .update();
        }
    }

    public Optional<CheckRow> check(UUID userId, UUID checkId) {
        return jdbc.sql("SELECT " + CHECK_COLUMNS + " FROM ownership_checks c WHERE c.id = :id AND c.user_id = :u")
                .param("id", checkId).param("u", userId).query(CHECK).optional();
    }

    public Optional<CheckRow> openCheck(UUID userId, UUID evidenceId) {
        return jdbc.sql("SELECT " + CHECK_COLUMNS + """
                 FROM ownership_checks c
                WHERE c.user_id = :u AND c.evidence_id = :e AND c.status = 'OPEN'
                ORDER BY c.started_at DESC LIMIT 1""")
                .param("u", userId).param("e", evidenceId).query(CHECK).optional();
    }

    public Optional<Instant> lastFinished(UUID userId, UUID evidenceId) {
        return jdbc.sql("""
                SELECT max(finished_at) AS at FROM ownership_checks
                WHERE user_id = :u AND evidence_id = :e AND status = 'FINISHED'""")
                .param("u", userId).param("e", evidenceId)
                .query((rs, n) -> Optional.ofNullable(Db.instant(rs, "at")))
                .single();
    }

    /** A finished check whose owner's profile is public, with that profile's handle and the repository. */
    public Optional<CheckRow> publicCheck(UUID checkId) {
        return jdbc.sql("SELECT " + CHECK_COLUMNS + """
                 FROM ownership_checks c JOIN profiles p ON p.user_id = c.user_id
                WHERE c.id = :id AND c.status = 'FINISHED' AND p.is_public""")
                .param("id", checkId).query(CHECK).optional();
    }

    /** The repository a check is about, and its owner's handle (null before they set up a profile). */
    public PublicHeader header(UUID checkId) {
        return jdbc.sql("""
                SELECT p.handle, e.title, e.url FROM ownership_checks c
                JOIN evidence e ON e.id = c.evidence_id LEFT JOIN profiles p ON p.user_id = c.user_id
                WHERE c.id = :id""")
                .param("id", checkId)
                .query((rs, n) -> new PublicHeader(rs.getString("handle"), rs.getString("title"), rs.getString("url")))
                .single();
    }

    public List<QuestionRow> questions(UUID checkId) {
        return jdbc.sql("""
                SELECT id, position, path, start_line, end_line, language, code, concept, prompt, rubric, identifiers,
                       covers, answer, answered_at, millis, pastes, focus_lost, score, feedback, flags
                FROM ownership_questions WHERE check_id = :c ORDER BY position""")
                .param("c", checkId).query(QUESTION).list();
    }

    /** Saves or replaces an answer while the check is open. Returns false when there is no such question. */
    public boolean saveAnswer(UUID checkId, int position, String answer, long millis, int pastes, int focusLost) {
        return jdbc.sql("""
                UPDATE ownership_questions SET answer = :a, answered_at = now(), millis = :ms, pastes = :p, focus_lost = :f
                WHERE check_id = :c AND position = :pos""")
                .param("a", answer).param("ms", millis).param("p", pastes).param("f", focusLost)
                .param("c", checkId).param("pos", position)
                .update() == 1;
    }

    @Transactional
    public void finish(UUID checkId, int score, boolean defended, String summary, String gradedBy, List<GradedQuestion> graded) {
        for (GradedQuestion g : graded) {
            jdbc.sql("UPDATE ownership_questions SET score = :s, feedback = :f, flags = :flags WHERE id = :id")
                    .param("s", g.score()).param("f", g.feedback()).param("flags", String.join("\n", g.flags()))
                    .param("id", g.id())
                    .update();
        }
        jdbc.sql("""
                UPDATE ownership_checks SET status = 'FINISHED', finished_at = now(), score = :s, defended = :d,
                    summary = :summary, graded_by = :by
                WHERE id = :id AND status = 'OPEN'""")
                .param("s", score).param("d", defended).param("summary", summary).param("by", gradedBy).param("id", checkId)
                .update();
    }

    // ------------------------------------------------------------------ text lists

    static String joinRubric(List<List<String>> rubric) {
        List<String> lines = new ArrayList<>();
        for (List<String> group : rubric) lines.add(String.join("|", group));
        return String.join("\n", lines);
    }

    static List<List<String>> rubric(String text) {
        List<List<String>> out = new ArrayList<>();
        for (String line : lines(text)) out.add(List.of(line.split("\\|")));
        return out;
    }

    static List<String> lines(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("\n")).filter(s -> !s.isBlank()).toList();
    }

    private static Object longOrNull(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
