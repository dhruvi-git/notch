package dev.notch.api.portfolio;

import dev.notch.api.portfolio.core.SkillCatalog;
import dev.notch.api.portfolio.core.SkillProfile.EvidenceItem;
import dev.notch.api.support.Db;
import dev.notch.engine.score.SkillScoring;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Profiles, evidence, evidence-to-skill links and claimed or hidden skills. Every query is scoped to a user. */
@Repository
public class PortfolioRepository {

    public record Profile(UUID userId, String handle, String headline, String domain, boolean isPublic) {
    }

    /** Evidence as stored, plus every skill link and whether the candidate approved it. */
    public record StoredEvidence(UUID id, UUID userId, SkillScoring.Kind kind, String title, String url, String description,
                                 int ownCommits, int activeWeeks, boolean fork, UUID duplicateOf, double chatDepth,
                                 Instant lastActive, Map<Integer, Boolean> skills) {
        /** For scoring: only approved links count. */
        public EvidenceItem toItem() {
            Set<Integer> approved = new HashSet<>();
            skills.forEach((id, ok) -> {
                if (ok) approved.add(id);
            });
            return new EvidenceItem(id, kind, title, url, ownCommits, activeWeeks, fork, duplicateOf != null,
                    lastActive, chatDepth, approved);
        }
    }

    public record UserSkills(Set<Integer> claimed, Set<Integer> hidden) {
    }

    private static final RowMapper<Profile> PROFILE = (rs, n) -> new Profile(Db.uuid(rs, "user_id"),
            rs.getString("handle"), rs.getString("headline"), rs.getString("domain"), rs.getBoolean("is_public"));

    private final JdbcClient jdbc;

    public PortfolioRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ catalogue

    public List<SkillCatalog.Skill> skills() {
        return jdbc.sql("SELECT id, name, domain FROM skills ORDER BY id")
                .query((rs, n) -> new SkillCatalog.Skill(rs.getInt("id"), rs.getString("name"), rs.getString("domain")))
                .list();
    }

    public Map<String, Integer> aliases() {
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql("SELECT alias, skill_id FROM skill_aliases")
                .query((rs, n) -> Map.entry(rs.getString("alias"), rs.getInt("skill_id")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    // ------------------------------------------------------------------ profiles

    public Optional<Profile> profile(UUID userId) {
        return jdbc.sql("SELECT user_id, handle, headline, domain, is_public FROM profiles WHERE user_id = :u")
                .param("u", userId).query(PROFILE).optional();
    }

    public Optional<Profile> publicProfile(String handle) {
        return jdbc.sql("SELECT user_id, handle, headline, domain, is_public FROM profiles WHERE handle = :h AND is_public")
                .param("h", handle).query(PROFILE).optional();
    }

    /** Creates or updates the profile. A handle taken by someone else fails on the unique index. */
    public void upsertProfile(UUID userId, String handle, String headline, String domain, boolean isPublic) {
        jdbc.sql("""
                INSERT INTO profiles (user_id, handle, headline, domain, is_public)
                VALUES (:u, :h, :headline, :domain, :pub)
                ON CONFLICT (user_id) DO UPDATE SET handle = EXCLUDED.handle, headline = EXCLUDED.headline,
                    domain = EXCLUDED.domain, is_public = EXCLUDED.is_public, updated_at = now()""")
                .param("u", userId).param("h", handle).param("headline", headline).param("domain", domain)
                .param("pub", isPublic)
                .update();
    }

    public List<Profile> publicProfiles() {
        return jdbc.sql("SELECT user_id, handle, headline, domain, is_public FROM profiles WHERE is_public ORDER BY handle")
                .query(PROFILE).list();
    }

    // ------------------------------------------------------------------ evidence

    public void insertEvidence(UUID id, UUID userId, SkillScoring.Kind kind, String title, String url, String description,
                               int ownCommits, int activeWeeks, boolean fork, UUID duplicateOf, double chatDepth,
                               String rootSha, String treeSha, Instant lastActive) {
        jdbc.sql("""
                INSERT INTO evidence (id, user_id, kind, title, url, description, own_commits, active_weeks, is_fork,
                                      duplicate_of, chat_depth, root_sha, tree_sha, last_active_at)
                VALUES (:id, :u, :kind, :title, :url, :description, :commits, :weeks, :fork, :dup, :depth,
                        :root, :tree, :last)""")
                .param("id", id).param("u", userId).param("kind", kind.name()).param("title", title)
                .param("url", url).param("description", description).param("commits", ownCommits)
                .param("weeks", activeWeeks).param("fork", fork).param("dup", duplicateOf).param("depth", chatDepth)
                .param("root", rootSha).param("tree", treeSha).param("last", Db.ts(lastActive))
                .update();
    }

    public void linkSkills(UUID evidenceId, Collection<Integer> skillIds, boolean approved) {
        for (int skillId : skillIds) {
            jdbc.sql("""
                    INSERT INTO evidence_skills (evidence_id, skill_id, approved) VALUES (:e, :s, :a)
                    ON CONFLICT (evidence_id, skill_id) DO NOTHING""")
                    .param("e", evidenceId).param("s", skillId).param("a", approved)
                    .update();
        }
    }

    /** Sets one link's approval. Returns false when the evidence is not the user's or the link does not exist. */
    public boolean setLinkApproved(UUID userId, UUID evidenceId, int skillId, boolean approved) {
        return jdbc.sql("""
                UPDATE evidence_skills es SET approved = :a
                FROM evidence e
                WHERE es.evidence_id = e.id AND e.id = :e AND e.user_id = :u AND es.skill_id = :s""")
                .param("a", approved).param("e", evidenceId).param("u", userId).param("s", skillId)
                .update() == 1;
    }

    public boolean deleteEvidence(UUID userId, UUID evidenceId) {
        return jdbc.sql("DELETE FROM evidence WHERE id = :e AND user_id = :u")
                .param("e", evidenceId).param("u", userId).update() == 1;
    }

    public List<StoredEvidence> evidence(UUID userId) {
        return evidenceFor(List.of(userId)).getOrDefault(userId, List.of());
    }

    /** Every piece of evidence of the given users, grouped by user, newest activity first. Two queries in all. */
    public Map<UUID, List<StoredEvidence>> evidenceFor(Collection<UUID> userIds) {
        Map<UUID, List<StoredEvidence>> out = new LinkedHashMap<>();
        if (userIds.isEmpty()) return out;
        record Link(UUID evidenceId, int skillId, boolean approved) {
        }
        Map<UUID, Map<Integer, Boolean>> links = new HashMap<>();
        jdbc.sql("""
                SELECT es.evidence_id, es.skill_id, es.approved
                FROM evidence_skills es JOIN evidence e ON e.id = es.evidence_id
                WHERE e.user_id IN (:users)""")
                .param("users", userIds)
                .query((rs, n) -> new Link(Db.uuid(rs, "evidence_id"), rs.getInt("skill_id"), rs.getBoolean("approved")))
                .list()
                .forEach(l -> links.computeIfAbsent(l.evidenceId(), k -> new LinkedHashMap<>()).put(l.skillId(), l.approved()));
        List<StoredEvidence> rows = jdbc.sql("""
                SELECT id, user_id, kind, title, url, description, own_commits, active_weeks, is_fork, duplicate_of,
                       chat_depth, last_active_at
                FROM evidence WHERE user_id IN (:users) ORDER BY last_active_at DESC""")
                .param("users", userIds)
                .query((rs, n) -> {
                    UUID id = Db.uuid(rs, "id");
                    return new StoredEvidence(id, Db.uuid(rs, "user_id"), SkillScoring.Kind.valueOf(rs.getString("kind")),
                            rs.getString("title"), rs.getString("url"), rs.getString("description"),
                            rs.getInt("own_commits"), rs.getInt("active_weeks"), rs.getBoolean("is_fork"),
                            Db.uuid(rs, "duplicate_of"), rs.getDouble("chat_depth"), Db.instant(rs, "last_active_at"),
                            links.getOrDefault(id, Map.of()));
                })
                .list();
        for (StoredEvidence e : rows) out.computeIfAbsent(e.userId(), k -> new ArrayList<>()).add(e);
        return out;
    }

    // ------------------------------------------------------------------ claimed and hidden skills

    public UserSkills userSkills(UUID userId) {
        return userSkillsFor(List.of(userId)).getOrDefault(userId, new UserSkills(Set.of(), Set.of()));
    }

    public Map<UUID, UserSkills> userSkillsFor(Collection<UUID> userIds) {
        record Row(UUID userId, int skillId, boolean claimed, boolean hidden) {
        }
        Map<UUID, Set<Integer>> claimed = new HashMap<>(), hidden = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<Row> rows = jdbc.sql("SELECT user_id, skill_id, claimed, hidden FROM user_skills WHERE user_id IN (:users)")
                    .param("users", userIds)
                    .query((rs, n) -> new Row(Db.uuid(rs, "user_id"), rs.getInt("skill_id"), rs.getBoolean("claimed"),
                            rs.getBoolean("hidden")))
                    .list();
            for (Row r : rows) {
                if (r.claimed()) claimed.computeIfAbsent(r.userId(), k -> new HashSet<>()).add(r.skillId());
                if (r.hidden()) hidden.computeIfAbsent(r.userId(), k -> new HashSet<>()).add(r.skillId());
            }
        }
        Map<UUID, UserSkills> out = new HashMap<>();
        for (UUID u : userIds) {
            out.put(u, new UserSkills(claimed.getOrDefault(u, Set.of()), hidden.getOrDefault(u, Set.of())));
        }
        return out;
    }

    public void setUserSkill(UUID userId, int skillId, boolean claimed, boolean hidden) {
        jdbc.sql("""
                INSERT INTO user_skills (user_id, skill_id, claimed, hidden) VALUES (:u, :s, :c, :h)
                ON CONFLICT (user_id, skill_id) DO UPDATE SET claimed = EXCLUDED.claimed, hidden = EXCLUDED.hidden""")
                .param("u", userId).param("s", skillId).param("c", claimed).param("h", hidden)
                .update();
    }
}
