package dev.notch.api.insights;

import dev.notch.api.config.ApiException;
import dev.notch.api.insights.core.Insights;
import dev.notch.api.insights.core.MemoryImport;
import dev.notch.api.insights.core.Read;
import dev.notch.api.portfolio.CandidatePool;
import dev.notch.api.portfolio.CatalogService;
import dev.notch.api.portfolio.PortfolioRepository;
import dev.notch.api.portfolio.PortfolioService;
import dev.notch.api.portfolio.ProfileView;
import dev.notch.api.portfolio.core.SkillCatalog;
import dev.notch.api.security.CurrentUser;
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

/** The candidate's read (who they are as a builder), and bringing in what their AI assistant remembers. */
@Service
public class InsightService {

    /** @param goal the role they're aiming for, or null */
    public record MyRead(Read read, String goal) {
    }

    public record ImportedSkill(UUID evidenceId, int skillId, String name, String mode, String note) {
    }

    public record ImportedProject(UUID evidenceId, String title, List<String> skills) {
    }

    /**
     * @param skippedProjects projects already on the profile under the same title
     * @param unrecognised    skill names the reply used that aren't in Notch's catalogue
     */
    public record MemoryImportResult(List<ImportedSkill> skills, List<ImportedProject> projects, int skippedProjects,
                                     List<String> unrecognised, String goal) {
    }

    /** How strongly each kind of memory counts once approved (chat evidence carries at most 0.2 of a skill). */
    static double depth(MemoryImport.Mode mode) {
        return switch (mode) {
            case USED -> 0.7;
            case LEARNING -> 0.5;
            case ASKED -> 0.3;
        };
    }

    private final PortfolioService portfolio;
    private final PortfolioRepository repo;
    private final CatalogService catalog;
    private final CandidatePool pool;
    private final CurrentUser currentUser;

    public InsightService(PortfolioService portfolio, PortfolioRepository repo, CatalogService catalog, CandidatePool pool,
                          CurrentUser currentUser) {
        this.portfolio = portfolio;
        this.repo = repo;
        this.catalog = catalog;
        this.pool = pool;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ the read

    public MyRead mine() {
        UUID me = currentUser.id();
        Read read = build(me, portfolio.mine());
        return new MyRead(read, repo.goal(me).orElse(null));
    }

    public Read publicRead(String handle) {
        String h = handle == null ? "" : handle.strip().toLowerCase(Locale.ROOT);
        PortfolioRepository.Profile p = repo.publicProfile(h).orElseThrow(() -> ApiException.notFound("A public profile called @" + h));
        return build(p.userId(), portfolio.publicProfile(h)).publicView();
    }

    private Read build(UUID userId, ProfileView view) {
        Set<Integer> hidden = repo.userSkills(userId).hidden();
        List<Insights.Skill> skills = view.skills().stream()
                .map(s -> new Insights.Skill(s.id(), s.name(), s.domain(), s.score(), s.level(), s.topPercent()))
                .toList();
        List<Insights.Work> work = new ArrayList<>();
        for (PortfolioRepository.StoredEvidence e : repo.evidence(userId)) {
            Set<Integer> approved = new HashSet<>(), pending = new HashSet<>();
            e.skills().forEach((id, ok) -> {
                if (!hidden.contains(id)) (ok ? approved : pending).add(id);
            });
            work.add(new Insights.Work(e.id(), e.kind().name(), e.title(), e.ownCommits(), e.activeWeeks(), e.fork(),
                    e.duplicateOf() != null, e.firstActive(), e.lastActive(), approved, pending,
                    e.ownership() != null && e.ownership().defended(), e.weekdays(), e.hours(), e.chatSource(), e.chatMode(),
                    e.chatConversations(), e.chatNote()));
        }
        Map<Integer, String> names = new HashMap<>();
        for (SkillCatalog.Skill s : catalog.catalog().all()) names.put(s.id(), s.name());
        return Insights.read(skills, work, names, Instant.now());
    }

    public MyRead setGoal(String goal) {
        String g = goal == null ? "" : goal.strip();
        if (g.isEmpty() || g.length() > 80) throw ApiException.badRequest("invalid_goal", "Describe the role in 1 to 80 characters.");
        repo.setGoal(currentUser.id(), g);
        return mine();
    }

    // ------------------------------------------------------------------ memory import

    public String prompt() {
        return MemoryImport.PROMPT;
    }

    /**
     * Reads a reply to the memory prompt and adds what it found, unapproved: skills as AI-history
     * evidence (replacing the previous memory import) and projects not already on the profile.
     */
    @Transactional
    public MemoryImportResult importMemory(String text) {
        UUID me = currentUser.id();
        MemoryImport.Parsed parsed;
        try {
            parsed = MemoryImport.parse(text, catalog.catalog());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unreadable_reply", e.getMessage());
        }
        if (parsed.isEmpty()) {
            throw new ApiException(HttpStatus.valueOf(422), "nothing_found",
                    "The reply had no skills, projects or goal that Notch recognises. If your assistant said it doesn't "
                            + "remember much, try Claude or ChatGPT with memory turned on.");
        }
        repo.deleteChatEvidence(me, "MEMORY");
        Instant now = Instant.now();
        List<ImportedSkill> skills = new ArrayList<>();
        for (MemoryImport.SkillLine s : parsed.skills()) {
            UUID id = UUID.randomUUID();
            String title = s.skill().name() + ": " + switch (s.mode()) {
                case USED -> "used, from AI memory";
                case LEARNING -> "learning, from AI memory";
                case ASKED -> "asked about, from AI memory";
            };
            repo.insertEvidence(id, me, dev.notch.engine.score.SkillScoring.Kind.CHAT_EXPORT, title, null, null, 0, 0, false,
                    null, depth(s.mode()), null, null, now);
            repo.linkSkills(id, List.of(s.skill().id()), false);
            repo.setChatMeta(id, "MEMORY", s.mode().name(), 0, s.note());
            skills.add(new ImportedSkill(id, s.skill().id(), s.skill().name(), s.mode().name(), s.note()));
        }
        Set<String> existing = repo.projectTitles(me);
        List<ImportedProject> projects = new ArrayList<>();
        int skipped = 0;
        for (MemoryImport.ProjectLine p : parsed.projects()) {
            if (!existing.add(p.title().toLowerCase(Locale.ROOT))) {
                skipped++;
                continue;
            }
            UUID id = UUID.randomUUID();
            repo.insertEvidence(id, me, dev.notch.engine.score.SkillScoring.Kind.PROJECT, p.title(), null, p.description(), 0, 0,
                    false, null, 0, null, null, now);
            if (!p.skills().isEmpty()) repo.linkSkills(id, p.skills().stream().map(SkillCatalog.Skill::id).toList(), false);
            repo.setChatMeta(id, "MEMORY", null, 0, null);
            projects.add(new ImportedProject(id, p.title(), p.skills().stream().map(SkillCatalog.Skill::name).toList()));
        }
        if (parsed.goal() != null) repo.setGoal(me, parsed.goal());
        pool.invalidate();
        return new MemoryImportResult(skills, projects, skipped, parsed.unrecognised(), parsed.goal());
    }
}
