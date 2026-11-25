package dev.notch.api.recruit;

import dev.notch.api.config.ApiException;
import dev.notch.api.portfolio.CandidatePool;
import dev.notch.api.portfolio.CatalogService;
import dev.notch.api.portfolio.core.SkillCatalog;
import dev.notch.api.portfolio.core.SkillProfile.Standing;
import dev.notch.api.recruit.core.JobParser;
import dev.notch.api.recruit.core.Matching;
import dev.notch.api.security.CurrentUser;
import dev.notch.engine.score.MatchScoring;
import dev.notch.engine.score.SkillScoring.Level;
import dev.notch.engine.select.RandomizedSelect;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The recruiter side: create a drive from a job description, edit its roles, shortlist
 * candidates for a role, and assign the whole drive's seats with max-flow.
 */
@Service
public class RecruitService {

    static final int MAX_ROLES = 10;
    static final int MAX_SHORTLIST = 100;
    static final int MAX_NEAR_MISSES = 5;
    static final double DEFAULT_THRESHOLD = 50;

    /** A role as the recruiter submits it. */
    public record RoleInput(String name, int seats, double threshold, List<RequirementInput> requirements) {
    }

    public record RequirementInput(int skillId, double weight, boolean mustHave, String minLevel) {
    }

    private final RecruitRepository repo;
    private final CatalogService catalog;
    private final CandidatePool pool;
    private final CurrentUser currentUser;

    public RecruitService(RecruitRepository repo, CatalogService catalog, CandidatePool pool, CurrentUser currentUser) {
        this.repo = repo;
        this.catalog = catalog;
        this.pool = pool;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ drives

    /** Creates a drive with one draft role whose requirements are read from the description. */
    @Transactional
    public DriveView.Drive create(String title, String description) {
        List<JobParser.DraftRequirement> draft;
        try {
            draft = JobParser.parse(description, catalog.catalog());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("empty_description", "Paste the job description to start a drive.");
        }
        UUID id = UUID.randomUUID();
        repo.insertDrive(id, currentUser.id(), title.strip(), description.strip());
        List<RecruitRepository.Requirement> reqs = draft.stream()
                .map(d -> new RecruitRepository.Requirement(d.skill().id(), d.weight(), d.mustHave(), d.minLevel().name()))
                .toList();
        repo.replaceRoles(id, List.of(new RecruitRepository.Role(UUID.randomUUID(), title.strip(), 1, DEFAULT_THRESHOLD, reqs)));
        return get(id);
    }

    public List<DriveView.Summary> list() {
        return repo.drives(currentUser.id()).stream()
                .map(d -> new DriveView.Summary(d.id(), d.title(), d.createdAt())).toList();
    }

    public DriveView.Drive get(UUID driveId) {
        RecruitRepository.Drive d = require(driveId);
        return new DriveView.Drive(d.id(), d.title(), d.description(), d.createdAt(),
                repo.roles(d.id()).stream().map(this::roleView).toList());
    }

    public void delete(UUID driveId) {
        if (!repo.deleteDrive(currentUser.id(), driveId)) throw ApiException.notFound("That drive");
    }

    @Transactional
    public DriveView.Drive saveRoles(UUID driveId, List<RoleInput> roles) {
        require(driveId);
        if (roles == null || roles.isEmpty() || roles.size() > MAX_ROLES) {
            throw ApiException.badRequest("invalid_roles", "A drive has between 1 and " + MAX_ROLES + " roles.");
        }
        SkillCatalog cat = catalog.catalog();
        List<RecruitRepository.Role> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (RoleInput r : roles) {
            String name = r.name() == null ? "" : r.name().strip();
            if (name.isEmpty()) throw ApiException.badRequest("invalid_role", "Every role needs a name.");
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw ApiException.badRequest("duplicate_role", "Two roles are called " + name + ".");
            if (r.seats() < 0 || r.seats() > 10_000) throw ApiException.badRequest("invalid_seats", name + ": seats must be between 0 and 10,000.");
            if (!(r.threshold() >= 0 && r.threshold() <= 100)) throw ApiException.badRequest("invalid_threshold", name + ": the minimum match must be between 0 and 100.");
            List<RequirementInput> reqs = r.requirements() == null ? List.of() : r.requirements();
            if (reqs.isEmpty() || reqs.size() > JobParser.MAX_REQUIREMENTS) {
                throw ApiException.badRequest("invalid_requirements", name + ": add between 1 and " + JobParser.MAX_REQUIREMENTS + " required skills.");
            }
            Set<Integer> seen = new HashSet<>();
            List<RecruitRepository.Requirement> stored = new ArrayList<>();
            for (RequirementInput q : reqs) {
                if (cat.get(q.skillId()).isEmpty()) throw ApiException.badRequest("unknown_skill", name + ": unknown skill id " + q.skillId() + ".");
                if (!seen.add(q.skillId())) throw ApiException.badRequest("duplicate_requirement", name + ": a skill is listed twice.");
                if (!(q.weight() > 0 && q.weight() <= 10)) throw ApiException.badRequest("invalid_weight", name + ": weights are between 0 and 10.");
                stored.add(new RecruitRepository.Requirement(q.skillId(), q.weight(), q.mustHave(), parseLevel(q.minLevel()).name()));
            }
            out.add(new RecruitRepository.Role(UUID.randomUUID(), name, r.seats(), r.threshold(), stored));
        }
        repo.replaceRoles(driveId, out);
        return get(driveId);
    }

    // ------------------------------------------------------------------ matching

    public DriveView.Shortlist shortlist(UUID driveId, UUID roleId, int k) {
        require(driveId);
        if (k < 1 || k > MAX_SHORTLIST) throw ApiException.badRequest("invalid_k", "Ask for between 1 and " + MAX_SHORTLIST + " candidates.");
        RecruitRepository.Role role = repo.roles(driveId).stream().filter(r -> r.id().equals(roleId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("That role"));
        List<MatchScoring.Requirement> reqs = requirements(role);

        CandidatePool.Snapshot snap = pool.snapshot();
        List<Matching.Candidate> candidates = candidates(snap);
        List<Matching.Ranked> ranked = Matching.shortlist(reqs, candidates, k, new RandomizedSelect());
        int eligible = (int) candidates.stream()
                .filter(c -> MatchScoring.match(reqs, c.skills()).eligible()).count();

        Map<UUID, CandidatePool.Member> byId = new HashMap<>();
        for (CandidatePool.Member m : snap.members()) byId.put(m.userId(), m);
        List<DriveView.Candidate> out = new ArrayList<>();
        for (Matching.Ranked r : ranked) {
            List<DriveView.Term> terms = r.match().terms().stream()
                    .map(t -> new DriveView.Term(t.skill(), t.weight(), t.skillScore(),
                            t.level() == null ? null : t.level().name(), t.levelFactor(), round2(t.contribution())))
                    .toList();
            DriveView.Fix fix = r.match().score() < role.threshold()
                    ? MatchScoring.closestFix(reqs, skillsOf(candidates, r.userId()), role.threshold()).map(RecruitService::fix).orElse(null)
                    : null;
            out.add(new DriveView.Candidate(r.userId(), r.handle(), byId.get(r.userId()).headline(),
                    round2(r.match().score()), terms, fix));
        }

        // Near misses: missing exactly one must-have, and one improvement on it would reach the minimum.
        List<DriveView.NearMiss> near = new ArrayList<>();
        for (Matching.Candidate c : candidates) {
            MatchScoring.Match m = MatchScoring.match(reqs, c.skills());
            if (m.missingMustHaves().size() != 1) continue;
            MatchScoring.closestFix(reqs, c.skills(), role.threshold()).ifPresent(f ->
                    near.add(new DriveView.NearMiss(c.userId(), c.handle(), byId.get(c.userId()).headline(), fix(f))));
        }
        near.sort(java.util.Comparator.comparingDouble((DriveView.NearMiss n) -> n.fix().to() - n.fix().from())
                .thenComparing(DriveView.NearMiss::handle));
        return new DriveView.Shortlist(role.id(), role.name(), snap.members().size(), eligible, role.threshold(), out,
                List.copyOf(near.subList(0, Math.min(MAX_NEAR_MISSES, near.size()))));
    }

    @Transactional
    public DriveView.Assignment assign(UUID driveId) {
        require(driveId);
        List<RecruitRepository.Role> roles = repo.roles(driveId);
        List<Matching.RoleSpec> specs = new ArrayList<>();
        for (RecruitRepository.Role r : roles) {
            if (r.requirements().isEmpty()) {
                throw new ApiException(HttpStatus.valueOf(422), "no_requirements",
                        r.name() + " has no required skills yet. Add at least one before assigning seats.");
            }
            specs.add(new Matching.RoleSpec(r.id(), r.name(), r.seats(), r.threshold(), requirements(r)));
        }
        Matching.DriveResult result = Matching.assign(specs, candidates(pool.snapshot()));

        RecruitRepository.Run run = new RecruitRepository.Run(UUID.randomUUID(), result.totalSeats(), result.greedyFilled(),
                result.augmentingPaths(), result.millis(), Instant.now(), result.greedyScore(), result.maxFlowFilled(),
                result.maxFlowScore(), result.bestFitScore(), result.bestFitMillis());
        List<RecruitRepository.Placement> placements = result.placements().stream()
                .map(p -> new RecruitRepository.Placement(p.roleId(), p.userId(), p.handle(), p.score(), p.moved())).toList();
        repo.insertRun(run, driveId, placements);

        Map<UUID, Integer> eligible = new HashMap<>();
        for (Matching.RoleOutcome o : result.roles()) eligible.put(o.roleId(), o.eligible());
        return assignmentView(run, roles, placements, eligible);
    }

    public DriveView.Assignment latestAssignment(UUID driveId) {
        require(driveId);
        RecruitRepository.Run run = repo.latestRun(driveId)
                .orElseThrow(() -> ApiException.notFoundWith("no_assignment", "Seats have not been assigned for this drive yet."));
        return assignmentView(run, repo.roles(driveId), repo.placements(run.id()), Map.of());
    }

    // ------------------------------------------------------------------ helpers

    private DriveView.Assignment assignmentView(RecruitRepository.Run run, List<RecruitRepository.Role> roles,
                                                List<RecruitRepository.Placement> placements, Map<UUID, Integer> eligible) {
        List<DriveView.RoleResult> results = new ArrayList<>();
        for (RecruitRepository.Role r : roles) {
            List<DriveView.Placement> in = placements.stream().filter(p -> p.roleId().equals(r.id()))
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .map(p -> new DriveView.Placement(p.userId(), p.handle(), round2(p.score()), p.moved())).toList();
            results.add(new DriveView.RoleResult(r.id(), r.name(), r.seats(), in.size(), eligible.get(r.id()), in));
        }
        return new DriveView.Assignment(run.runAt(), run.totalSeats(), placements.size(), round2(run.bestFitScore()),
                run.greedyFilled(), round2(run.greedyScore()), results);
    }

    private List<Matching.Candidate> candidates(CandidatePool.Snapshot snap) {
        List<Matching.Candidate> out = new ArrayList<>(snap.members().size());
        for (CandidatePool.Member m : snap.members()) {
            Map<String, MatchScoring.CandidateSkill> skills = new HashMap<>();
            for (Standing s : m.skills()) skills.put(s.skill().name(), new MatchScoring.CandidateSkill(s.score(), s.level()));
            out.add(new Matching.Candidate(m.userId(), m.handle(), skills));
        }
        return out;
    }

    private List<MatchScoring.Requirement> requirements(RecruitRepository.Role role) {
        SkillCatalog cat = catalog.catalog();
        List<MatchScoring.Requirement> out = new ArrayList<>();
        for (RecruitRepository.Requirement q : role.requirements()) {
            cat.get(q.skillId()).ifPresent(s -> out.add(
                    new MatchScoring.Requirement(s.name(), q.weight(), q.mustHave(), Level.valueOf(q.minLevel()))));
        }
        if (out.isEmpty()) {
            throw new ApiException(HttpStatus.valueOf(422), "no_requirements",
                    role.name() + " has no required skills yet. Add at least one first.");
        }
        return out;
    }

    private DriveView.Role roleView(RecruitRepository.Role r) {
        SkillCatalog cat = catalog.catalog();
        List<DriveView.Requirement> reqs = r.requirements().stream()
                .map(q -> new DriveView.Requirement(q.skillId(), cat.get(q.skillId()).map(SkillCatalog.Skill::name).orElse("?"),
                        q.weight(), q.mustHave(), q.minLevel()))
                .toList();
        return new DriveView.Role(r.id(), r.name(), r.seats(), r.threshold(), reqs);
    }

    private RecruitRepository.Drive require(UUID driveId) {
        return repo.drive(currentUser.id(), driveId).orElseThrow(() -> ApiException.notFound("That drive"));
    }

    private static Level parseLevel(String s) {
        try {
            return Level.valueOf(s == null ? "SHOWN" : s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_level", "Evidence level must be claimed, shown or verified.");
        }
    }

    private static Double round2(Double v) {
        return v == null ? null : round2(v.doubleValue());
    }

    private static DriveView.Fix fix(MatchScoring.Fix f) {
        return new DriveView.Fix(f.skill(), round2(f.from()), round2(f.to()), f.minLevel().name(), f.levelUp());
    }

    private static Map<String, MatchScoring.CandidateSkill> skillsOf(List<Matching.Candidate> pool, UUID userId) {
        for (Matching.Candidate c : pool) if (c.userId().equals(userId)) return c.skills();
        return Map.of();
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
