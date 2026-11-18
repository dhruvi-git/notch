package dev.notch.api.portfolio;

import dev.notch.api.config.ApiException;
import dev.notch.api.portfolio.ProfileView.EvidenceView;
import dev.notch.api.portfolio.ProfileView.SkillRef;
import dev.notch.api.portfolio.ProfileView.SkillView;
import dev.notch.api.portfolio.core.SkillCatalog;
import dev.notch.api.portfolio.core.SkillProfile;
import dev.notch.api.portfolio.core.SkillProfile.Standing;
import dev.notch.api.security.CurrentUser;
import dev.notch.engine.score.PercentileBands;
import dev.notch.engine.score.SkillScoring;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** The candidate side: the profile, adding and removing evidence, and choosing which skills show. */
@Service
public class PortfolioService {

    static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,38}$");
    static final int MAX_SKILLS_PER_EVIDENCE = 20;

    private final PortfolioRepository repo;
    private final CatalogService catalog;
    private final CandidatePool pool;
    private final CurrentUser currentUser;

    public PortfolioService(PortfolioRepository repo, CatalogService catalog, CandidatePool pool, CurrentUser currentUser) {
        this.repo = repo;
        this.catalog = catalog;
        this.pool = pool;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ reading

    public ProfileView mine() {
        UUID me = currentUser.id();
        return view(me, repo.profile(me), true);
    }

    public ProfileView publicProfile(String handle) {
        String h = handle == null ? "" : handle.strip().toLowerCase(Locale.ROOT);
        PortfolioRepository.Profile p = repo.publicProfile(h)
                .orElseThrow(() -> ApiException.notFound("A public profile called @" + h));
        return view(p.userId(), Optional.of(p), false);
    }

    private ProfileView view(UUID userId, Optional<PortfolioRepository.Profile> profile, boolean owner) {
        SkillCatalog cat = catalog.catalog();
        List<PortfolioRepository.StoredEvidence> stored = repo.evidence(userId);
        PortfolioRepository.UserSkills us = repo.userSkills(userId);
        List<Standing> standings = SkillProfile.assemble(cat,
                stored.stream().map(PortfolioRepository.StoredEvidence::toItem).toList(),
                us.claimed(), us.hidden(), Instant.now());

        CandidatePool.Snapshot snap = pool.snapshot();
        List<SkillView> skills = new ArrayList<>();
        for (Standing s : standings) {
            PercentileBands bands = snap.bands().get(s.skill().id());
            OptionalInt band = bands == null || s.score() == 0 ? OptionalInt.empty() : bands.bandOf(s.score());
            skills.add(new SkillView(s.skill().id(), s.skill().name(), s.skill().domain(), s.score(), s.level().name(),
                    band.isPresent() ? band.getAsInt() : null, s.evidenceIds()));
        }

        List<EvidenceView> evidence = new ArrayList<>();
        for (PortfolioRepository.StoredEvidence e : stored) {
            List<SkillRef> approved = new ArrayList<>(), pending = new ArrayList<>();
            for (Map.Entry<Integer, Boolean> link : e.skills().entrySet()) {
                if (us.hidden().contains(link.getKey())) continue;
                cat.get(link.getKey()).ifPresent(sk -> (link.getValue() ? approved : pending).add(new SkillRef(sk.id(), sk.name())));
            }
            // The public sees evidence only once it backs at least one approved skill.
            if (!owner && approved.isEmpty()) continue;
            evidence.add(new EvidenceView(e.id(), e.kind().name(), e.title(), e.url(), e.description(), e.ownCommits(),
                    e.activeWeeks(), e.fork(), e.duplicateOf() != null, e.lastActive(), approved,
                    owner ? pending : List.of()));
        }

        int poolSize = snap.members().size();
        return profile.map(p -> new ProfileView(true, p.handle(), p.headline(), p.domain(), p.isPublic(), skills, evidence, poolSize))
                .orElseGet(() -> new ProfileView(false, null, null, null, false, skills, evidence, poolSize));
    }

    public List<SkillRef> autocomplete(String q) {
        if (q == null || q.isBlank()) return List.of();
        if (q.length() > 60) throw ApiException.badRequest("query_too_long", "Type at most 60 characters.");
        return catalog.catalog().autocomplete(q, 10).stream().map(s -> new SkillRef(s.id(), s.name())).toList();
    }

    // ------------------------------------------------------------------ writing

    public ProfileView saveProfile(String handle, String headline, String domain, boolean isPublic) {
        String h = handle == null ? "" : handle.strip().toLowerCase(Locale.ROOT);
        if (!HANDLE.matcher(h).matches()) {
            throw ApiException.badRequest("invalid_handle",
                    "A handle is 2 to 39 characters: lower-case letters, digits and hyphens, starting with a letter or digit.");
        }
        try {
            repo.upsertProfile(currentUser.id(), h, blankToNull(headline), blankToNull(domain), isPublic);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("handle_taken", "@" + h + " is already taken. Try another handle.");
        }
        pool.invalidate();
        return mine();
    }

    /**
     * Adds a project or activity by hand. Skills come from the names the candidate lists plus any
     * the catalogue finds in the title and description.
     */
    @Transactional
    public EvidenceView addProject(String title, String url, String description, List<String> skillNames, LocalDate lastActive) {
        SkillCatalog cat = catalog.catalog();
        Set<Integer> skillIds = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String name : skillNames == null ? List.<String>of() : skillNames) {
            if (name == null || name.isBlank()) continue;
            cat.resolve(name).ifPresentOrElse(s -> skillIds.add(s.id()), () -> unknown.add(name.strip()));
        }
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("unknown_skill", "Notch doesn't know these skills yet: " + String.join(", ", unknown)
                    + ". Pick from the suggestions.");
        }
        for (SkillCatalog.Skill s : cat.extract(title + "\n" + (description == null ? "" : description))) skillIds.add(s.id());
        if (skillIds.isEmpty()) {
            throw ApiException.badRequest("no_skills", "Add at least one skill this project shows.");
        }
        if (skillIds.size() > MAX_SKILLS_PER_EVIDENCE) {
            throw ApiException.badRequest("too_many_skills", "One project can back at most " + MAX_SKILLS_PER_EVIDENCE + " skills.");
        }
        String link = validUrl(url);
        Instant active = lastActive == null ? Instant.now() : lastActive.atStartOfDay(ZoneOffset.UTC).toInstant();
        if (active.isAfter(Instant.now())) active = Instant.now();

        UUID id = UUID.randomUUID();
        UUID me = currentUser.id();
        repo.insertEvidence(id, me, SkillScoring.Kind.PROJECT, title.strip(), link, blankToNull(description),
                0, 0, false, null, 0, null, null, active);
        repo.linkSkills(id, skillIds, true);
        pool.invalidate();
        List<SkillRef> refs = skillIds.stream().map(sid -> cat.get(sid).orElseThrow())
                .map(s -> new SkillRef(s.id(), s.name())).toList();
        return new EvidenceView(id, SkillScoring.Kind.PROJECT.name(), title.strip(), link, blankToNull(description), 0, 0,
                false, false, active, refs, List.of());
    }

    public void deleteEvidence(UUID evidenceId) {
        if (!repo.deleteEvidence(currentUser.id(), evidenceId)) throw ApiException.notFound("That evidence");
        pool.invalidate();
    }

    /** Claims a skill (shown as claimed until evidence backs it) or hides one from the profile. */
    public ProfileView setSkill(int skillId, boolean claimed, boolean hidden) {
        if (catalog.catalog().get(skillId).isEmpty()) throw ApiException.notFound("That skill");
        repo.setUserSkill(currentUser.id(), skillId, claimed, hidden);
        pool.invalidate();
        return mine();
    }

    /** Approves (or rejects) a skill that a chat export suggested. */
    public ProfileView setLink(UUID evidenceId, int skillId, boolean approved) {
        if (!repo.setLinkApproved(currentUser.id(), evidenceId, skillId, approved)) {
            throw ApiException.notFound("That suggested skill");
        }
        pool.invalidate();
        return mine();
    }

    // ------------------------------------------------------------------ helpers

    static String validUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = URI.create(url.strip());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && u.getHost() != null) return u.toString();
        } catch (IllegalArgumentException ignored) {
            // falls through to the error below
        }
        throw ApiException.badRequest("invalid_url", "Links must start with http:// or https://.");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
