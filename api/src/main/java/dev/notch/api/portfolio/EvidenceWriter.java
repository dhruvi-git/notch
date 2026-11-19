package dev.notch.api.portfolio;

import dev.notch.api.portfolio.core.ChatSkillSuggester;
import dev.notch.api.portfolio.core.DuplicateScreen;
import dev.notch.api.portfolio.core.RepoEvidence;
import dev.notch.engine.score.SkillScoring;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Writes synced evidence in one transaction each, after the network calls are done, so a
 * failed sync or upload leaves the portfolio as it was.
 */
@Component
public class EvidenceWriter {

    public record RepoWrite(int added, int updated, int removed, List<String> duplicates) {
    }

    private final PortfolioRepository repo;
    private final FingerprintIndex fingerprints;

    public EvidenceWriter(PortfolioRepository repo, FingerprintIndex fingerprints) {
        this.repo = repo;
        this.fingerprints = fingerprints;
    }

    /**
     * Adds or updates one evidence row per repository and removes repositories that are gone or no
     * longer count (deleted, now a fork, or without the candidate's commits). Repositories listed on
     * GitHub but not fetched this time (beyond the per-sync limit) keep their earlier evidence.
     *
     * @param keepUrls repositories listed on GitHub that may keep earlier evidence
     */
    @Transactional
    public RepoWrite writeRepos(UUID userId, List<RepoEvidence.Draft> drafts, Set<String> keepUrls) {
        DuplicateScreen screen = fingerprints.screen();
        Map<String, UUID> existing = repo.githubEvidence(userId);
        int added = 0, updated = 0;
        List<String> duplicates = new ArrayList<>();
        Set<String> written = new HashSet<>();
        for (RepoEvidence.Draft d : drafts) {
            Optional<DuplicateScreen.Owner> original = screen.check(userId, d.rootSha(), d.treeSha(), repo::fingerprintOwners);
            UUID dupOf = original.map(DuplicateScreen.Owner::evidenceId).orElse(null);
            if (dupOf != null) duplicates.add(d.title());
            UUID id = existing.get(d.url());
            if (id != null) {
                repo.updateRepoEvidence(id, userId, d.title(), d.description(), d.ownCommits(), d.activeWeeks(), dupOf,
                        d.rootSha(), d.treeSha(), d.lastActive());
                updated++;
            } else {
                id = UUID.randomUUID();
                repo.insertEvidence(id, userId, SkillScoring.Kind.GITHUB_REPO, d.title(), d.url(), d.description(),
                        d.ownCommits(), d.activeWeeks(), false, dupOf, 0, d.rootSha(), d.treeSha(), d.lastActive());
                added++;
            }
            repo.replaceLinks(id, d.skillIds());
            if (d.rhythm() != null) repo.setRhythm(id, d.rhythm().firstActive(), d.rhythm().weekdays(), d.rhythm().hours());
            if (dupOf == null) screen.add(d.rootSha(), d.treeSha());
            written.add(d.url());
        }
        List<UUID> stale = new ArrayList<>();
        existing.forEach((url, id) -> {
            if (!written.contains(url) && !keepUrls.contains(url)) stale.add(id);
        });
        int removed = repo.deleteEvidence(userId, stale);
        return new RepoWrite(added, updated, removed, List.copyOf(duplicates));
    }

    /** Replaces the candidate's chat-export suggestions: one unapproved evidence item per suggested skill. */
    @Transactional
    public List<UUID> replaceChatSuggestions(UUID userId, String source, List<ChatSkillSuggester.Suggestion> suggestions) {
        repo.deleteChatEvidence(userId, "EXPORT");
        List<UUID> ids = new ArrayList<>();
        for (ChatSkillSuggester.Suggestion s : suggestions) {
            UUID id = UUID.randomUUID();
            String title = "Discussed " + s.skill().name() + " in " + s.conversations() + " " + source + " conversations";
            Instant last = s.lastMentioned() == null ? Instant.now() : s.lastMentioned();
            repo.insertEvidence(id, userId, SkillScoring.Kind.CHAT_EXPORT, title, null, null, 0, 0, false, null,
                    s.depth(), null, null, last);
            repo.linkSkills(id, List.of(s.skill().id()), false);
            repo.setChatMeta(id, "EXPORT", "ASKED", s.conversations(), null);
            ids.add(id);
        }
        return ids;
    }
}
