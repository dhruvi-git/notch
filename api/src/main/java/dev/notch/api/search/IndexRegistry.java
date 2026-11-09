package dev.notch.api.search;

import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectRepository;
import dev.notch.api.search.core.ProjectIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Holds one {@link ProjectIndex} per project in memory.
 *
 * <ul>
 *   <li>The first search of a project builds its index and waits for it.</li>
 *   <li>After an import, the index is rebuilt in the background while searches keep using the
 *       previous one; the new index is swapped in with a single volatile write. This hides
 *       the suffix-array build time measured in phase 1.</li>
 *   <li>Indexes unused for 30 minutes are dropped to free memory.</li>
 * </ul>
 */
@Component
public class IndexRegistry implements DisposableBean {

    static final Duration IDLE_EVICTION = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(IndexRegistry.class);

    private static final class Holder {
        volatile ProjectIndex index;
        volatile long lastUsed = System.currentTimeMillis();
        volatile long version;
        boolean rebuilding;   // guarded by synchronized (this)
        boolean dirty;        // another rebuild was requested while one was running
    }

    private final ConcurrentHashMap<UUID, Holder> holders = new ConcurrentHashMap<>();
    private final ExecutorService rebuilder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "index-rebuild");
        t.setDaemon(true);
        return t;
    });
    private final IndexLoader loader;
    private final ProjectRepository projects;

    public IndexRegistry(IndexLoader loader, ProjectRepository projects) {
        this.loader = loader;
        this.projects = projects;
    }

    /** The project's current index, building it on first use. */
    public ProjectIndex get(Project project) {
        Holder h = holders.computeIfAbsent(project.id(), id -> new Holder());
        h.lastUsed = System.currentTimeMillis();
        ProjectIndex idx = h.index;
        if (idx != null) return idx;
        synchronized (h) {
            if (h.index == null) {
                h.index = loader.load(project.id(), project.userId());
                h.version++;
                log.info("Built index for project {}: {}", project.id(), h.index.stats());
            }
            return h.index;
        }
    }

    public long version(UUID projectId) {
        Holder h = holders.get(projectId);
        return h == null ? 0 : h.version;
    }

    /** Rebuilds in the background if the project's index is loaded; otherwise the next search builds it. */
    public void rebuildAsync(UUID projectId) {
        Holder h = holders.get(projectId);
        if (h == null) return;
        synchronized (h) {
            if (h.rebuilding) {
                h.dirty = true;
                return;
            }
            h.rebuilding = true;
        }
        rebuilder.submit(() -> rebuildLoop(projectId, h));
    }

    private void rebuildLoop(UUID projectId, Holder h) {
        try {
            while (true) {
                Project p = projects.findAnyOwner(projectId).orElse(null);
                if (p == null) {
                    holders.remove(projectId);
                    return;
                }
                ProjectIndex fresh = loader.load(p.id(), p.userId());
                h.index = fresh;
                h.version++;
                log.info("Rebuilt index for project {}: {}", projectId, fresh.stats());
                synchronized (h) {
                    if (!h.dirty) return;
                    h.dirty = false;
                }
            }
        } catch (RuntimeException e) {
            log.error("Index rebuild failed for project {}; keeping the previous index", projectId, e);
        } finally {
            synchronized (h) {
                h.rebuilding = false;
            }
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void evictIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_EVICTION.toMillis();
        holders.entrySet().removeIf(e -> e.getValue().lastUsed < cutoff && !e.getValue().rebuilding);
    }

    @Override
    public void destroy() {
        rebuilder.shutdownNow();
    }
}
