package dev.notch.api.ingest;

import dev.notch.engine.probabilistic.BloomFilter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skips chunks a project already has, before they are stored or embedded.
 *
 * <p>Each project gets a Bloom filter of its chunk hashes, loaded from the database on first
 * use. "Definitely new" answers skip the database entirely. "Possibly present" answers are
 * always confirmed with the exact lookup, and a confirmation that finds nothing is counted as
 * a false positive, so the import report shows the filter's real behaviour.
 *
 * <p>Hashes are added when a chunk is inserted. If that transaction later rolls back, the
 * filter holds a hash with no row behind it; that can only cause a false positive, which the
 * exact check catches, so correctness never depends on the filter.
 */
@Component
public class ChunkDeduplicator {

    public enum Verdict { NEW, DUPLICATE, FALSE_POSITIVE }

    static final double FALSE_POSITIVE_RATE = 0.01;
    static final int MIN_CAPACITY = 10_000;

    private record Entry(BloomFilter filter, long capacity) {
    }

    private final ConcurrentHashMap<UUID, Entry> filters = new ConcurrentHashMap<>();
    private final ChunkRepository chunks;

    public ChunkDeduplicator(ChunkRepository chunks) {
        this.chunks = chunks;
    }

    public Verdict check(UUID projectId, byte[] contentHash) {
        Entry e = entry(projectId);
        boolean maybe;
        synchronized (e.filter()) {
            maybe = e.filter().mightContain(contentHash);
        }
        if (!maybe) return Verdict.NEW;
        return chunks.exists(projectId, contentHash) ? Verdict.DUPLICATE : Verdict.FALSE_POSITIVE;
    }

    public void remember(UUID projectId, byte[] contentHash) {
        Entry e = entry(projectId);
        synchronized (e.filter()) {
            e.filter().add(contentHash);
            if (e.filter().insertions() > e.capacity()) {
                filters.remove(projectId, e);  // over capacity: rebuild larger on next use
            }
        }
    }

    /** Drops a project's filter, for example after the user deletes data. */
    public void evict(UUID projectId) {
        filters.remove(projectId);
    }

    private Entry entry(UUID projectId) {
        return filters.computeIfAbsent(projectId, id -> {
            List<byte[]> hashes = chunks.hashes(id);
            long capacity = Math.max(MIN_CAPACITY, hashes.size() * 2L);
            BloomFilter f = BloomFilter.create(capacity, FALSE_POSITIVE_RATE);
            for (byte[] h : hashes) f.add(h);
            return new Entry(f, capacity);
        });
    }
}
