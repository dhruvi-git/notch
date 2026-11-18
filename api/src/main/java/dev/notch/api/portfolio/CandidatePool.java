package dev.notch.api.portfolio;

import dev.notch.api.portfolio.core.SkillProfile;
import dev.notch.api.portfolio.core.SkillProfile.Standing;
import dev.notch.engine.score.PercentileBands;
import dev.notch.engine.select.RandomizedSelect;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every public candidate with their computed skills, and the percentile bands per skill.
 *
 * <p>Recruiters search this pool and public profiles show bands from it. Building it reads all
 * public profiles' evidence in a few queries and scores them, so the snapshot is kept for a
 * short time and rebuilt after it expires or after any candidate changes their portfolio.
 */
@Component
public class CandidatePool {

    public record Member(UUID userId, String handle, String headline, String domain, List<Standing> skills) {
    }

    public record Snapshot(List<Member> members, Map<Integer, PercentileBands> bands, Instant builtAt) {
    }

    static final Duration TTL = Duration.ofSeconds(30);

    private final PortfolioRepository repo;
    private final CatalogService catalog;
    private final Clock clock;
    private volatile Snapshot snapshot;

    public CandidatePool(PortfolioRepository repo, CatalogService catalog) {
        this.repo = repo;
        this.catalog = catalog;
        this.clock = Clock.systemUTC();
    }

    public Snapshot snapshot() {
        Snapshot s = snapshot;
        Instant now = clock.instant();
        if (s == null || s.builtAt().plus(TTL).isBefore(now)) {
            s = build(now);
            snapshot = s;
        }
        return s;
    }

    /** Called after a portfolio changes, so the next read sees it. */
    public void invalidate() {
        snapshot = null;
    }

    private Snapshot build(Instant now) {
        List<PortfolioRepository.Profile> profiles = repo.publicProfiles();
        List<UUID> ids = profiles.stream().map(PortfolioRepository.Profile::userId).toList();
        Map<UUID, List<PortfolioRepository.StoredEvidence>> evidence = repo.evidenceFor(ids);
        Map<UUID, PortfolioRepository.UserSkills> userSkills = repo.userSkillsFor(ids);

        List<Member> members = new ArrayList<>(profiles.size());
        Map<Integer, List<Double>> scoresBySkill = new HashMap<>();
        for (PortfolioRepository.Profile p : profiles) {
            List<SkillProfile.EvidenceItem> items = evidence.getOrDefault(p.userId(), List.of()).stream()
                    .map(PortfolioRepository.StoredEvidence::toItem).toList();
            PortfolioRepository.UserSkills us = userSkills.get(p.userId());
            List<Standing> skills = SkillProfile.assemble(catalog.catalog(), items, us.claimed(), us.hidden(), now);
            members.add(new Member(p.userId(), p.handle(), p.headline(), p.domain(), skills));
            for (Standing st : skills) {
                if (st.score() > 0) scoresBySkill.computeIfAbsent(st.skill().id(), k -> new ArrayList<>()).add(st.score());
            }
        }
        // Bands are among candidates who have any evidence for the skill.
        RandomizedSelect select = new RandomizedSelect();
        Map<Integer, PercentileBands> bands = new HashMap<>();
        scoresBySkill.forEach((skillId, list) -> {
            double[] arr = list.stream().mapToDouble(Double::doubleValue).toArray();
            bands.put(skillId, PercentileBands.of(arr, select));
        });
        return new Snapshot(List.copyOf(members), Map.copyOf(bands), now);
    }
}
