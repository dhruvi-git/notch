package dev.notch.api.portfolio.core;

import dev.notch.engine.probabilistic.BloomFilter;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks whether a repository someone connects was already claimed by someone else, so one
 * project cannot verify skills for two people.
 *
 * <p>Every claimed repository is known by two fingerprints: the SHA of its first commit (which
 * survives re-uploads that keep history) and the tree SHA of its latest commit (which catches an
 * exact copy pushed fresh). A Bloom filter over all fingerprints answers "definitely new" without
 * touching the database. A "possibly seen" answer is only a hint: it is always confirmed with an
 * exact lookup, and a hint the lookup does not confirm is counted as a false positive.
 */
public final class DuplicateScreen {

    /** Evidence already carrying a fingerprint. */
    public record Owner(UUID evidenceId, UUID userId, Instant createdAt) {
    }

    /** Exact lookup: every piece of evidence with this fingerprint. */
    public interface Lookup {
        List<Owner> owners(String sha);
    }

    private final BloomFilter filter;
    private long checks;
    private long hints;
    private long falsePositives;

    private DuplicateScreen(BloomFilter filter) {
        this.filter = filter;
    }

    /** A screen over the fingerprints already in the database, sized for growth to {@code capacity}. */
    public static DuplicateScreen of(Collection<String> known, long capacity) {
        DuplicateScreen s = new DuplicateScreen(BloomFilter.create(Math.max(capacity, known.size() * 2L), 0.01));
        for (String sha : known) if (sha != null) s.filter.add(sha);
        return s;
    }

    /**
     * The earliest evidence of another user with the same first-commit or tree fingerprint, if any.
     * The candidate's own earlier evidence (a re-sync) never counts as a duplicate.
     */
    public synchronized Optional<Owner> check(UUID userId, String rootSha, String treeSha, Lookup lookup) {
        Objects.requireNonNull(userId, "userId");
        Optional<Owner> found = Optional.empty();
        for (String sha : new String[]{rootSha, treeSha}) {
            if (sha == null || sha.isBlank()) continue;
            checks++;
            if (!filter.mightContain(sha)) continue; // definitely new: no database query
            hints++;
            List<Owner> owners = lookup.owners(sha);
            if (owners.isEmpty()) {
                falsePositives++;
                continue;
            }
            Optional<Owner> other = owners.stream().filter(o -> !o.userId().equals(userId))
                    .min(Comparator.comparing(Owner::createdAt));
            if (other.isPresent() && (found.isEmpty() || other.get().createdAt().isBefore(found.get().createdAt()))) {
                found = other;
            }
        }
        return found;
    }

    /** Records fingerprints of newly stored evidence. */
    public synchronized void add(String rootSha, String treeSha) {
        if (rootSha != null && !rootSha.isBlank()) filter.add(rootSha);
        if (treeSha != null && !treeSha.isBlank()) filter.add(treeSha);
    }

    public synchronized long checks() {
        return checks;
    }

    /** "Possibly seen" answers that went on to an exact lookup. */
    public synchronized long hints() {
        return hints;
    }

    public synchronized long falsePositives() {
        return falsePositives;
    }
}
