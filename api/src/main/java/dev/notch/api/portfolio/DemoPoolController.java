package dev.notch.api.portfolio;

import dev.notch.api.config.ApiException;
import dev.notch.api.portfolio.core.SkillCatalog;
import dev.notch.api.security.UserRepository;
import dev.notch.engine.score.SkillScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Local development only: fills the pool with synthetic, public candidate profiles so the
 * recruiter screens and the demo have people to rank. Profiles follow common fresher tracks
 * (data, ML, frontend, backend, plus all-rounders) with varied commit counts, active weeks
 * and ages. A fixed seed makes the pool the same on every machine. Running it again skips
 * candidates that already have evidence.
 */
@RestController
@Profile("local")
public class DemoPoolController {

    public record Seeded(int created, int skipped) {
    }

    static final int MAX_SIZE = 5_000;

    private static final Map<String, List<String>> TRACKS = Map.of(
            "Data analyst", List.of("SQL", "Python", "pandas", "Excel", "Power BI", "Tableau", "Statistics", "Data Visualization"),
            "ML engineer", List.of("Python", "Machine Learning", "scikit-learn", "PyTorch", "Deep Learning", "NLP", "pandas", "SQL"),
            "Frontend developer", List.of("JavaScript", "TypeScript", "React", "Next.js", "CSS", "HTML", "Tailwind CSS", "Figma"),
            "Backend developer", List.of("Java", "Spring Boot", "PostgreSQL", "Docker", "REST APIs", "Node.js", "Redis", "Git"));

    private final UserRepository users;
    private final PortfolioRepository repo;
    private final CatalogService catalog;
    private final CandidatePool pool;

    public DemoPoolController(UserRepository users, PortfolioRepository repo, CatalogService catalog, CandidatePool pool) {
        this.users = users;
        this.repo = repo;
        this.catalog = catalog;
        this.pool = pool;
    }

    @PostMapping("/api/dev/demo-pool")
    @Transactional
    public Seeded seed(@RequestParam(defaultValue = "300") int size) {
        if (size < 1 || size > MAX_SIZE) throw ApiException.badRequest("invalid_size", "size must be 1 to " + MAX_SIZE + ".");
        SkillCatalog cat = catalog.catalog();
        Random rnd = new Random(2026);
        List<String> trackNames = new ArrayList<>(TRACKS.keySet());
        trackNames.sort(String::compareTo);
        Instant now = Instant.now();
        int created = 0, skipped = 0;

        for (int i = 1; i <= size; i++) {
            String handle = String.format("demo-%04d", i);
            UUID userId = users.findOrCreateDev(handle);
            boolean allRounder = rnd.nextInt(5) == 0;
            String track = trackNames.get(rnd.nextInt(trackNames.size()));
            String headline = allRounder ? "Full-stack and data, final-year B.Tech" : track + ", final-year B.Tech";
            repo.upsertProfile(userId, handle, headline, allRounder ? "Generalist" : track, true);
            if (!repo.evidence(userId).isEmpty()) {
                skipped++;
                continue;
            }
            int items = 2 + rnd.nextInt(4);
            for (int e = 0; e < items; e++) {
                List<String> from = allRounder ? TRACKS.get(trackNames.get(rnd.nextInt(trackNames.size()))) : TRACKS.get(track);
                Set<Integer> skills = new LinkedHashSet<>();
                int n = 2 + rnd.nextInt(3);
                for (int s = 0; s < n; s++) cat.resolve(from.get(rnd.nextInt(from.size()))).ifPresent(sk -> skills.add(sk.id()));

                boolean isRepo = rnd.nextInt(4) != 0;
                boolean fork = isRepo && rnd.nextInt(10) == 0;
                int commits = isRepo ? (int) Math.round(Math.abs(rnd.nextGaussian()) * 25) : 0;
                int weeks = isRepo ? Math.min(commits, 1 + rnd.nextInt(10)) : 0;
                Instant last = now.minus(Duration.ofDays(rnd.nextInt(540)));
                UUID id = UUID.randomUUID();
                String title = (isRepo ? "repo-" : "Project ") + handle + "-" + (e + 1);
                repo.insertEvidence(id, userId, isRepo ? SkillScoring.Kind.GITHUB_REPO : SkillScoring.Kind.PROJECT,
                        title, null, "Synthetic demo evidence", commits, weeks, fork, null, 0, null, null, last);
                repo.linkSkills(id, skills, true);
            }
            created++;
        }
        pool.invalidate();
        return new Seeded(created, skipped);
    }
}
