package dev.notch.api.jobs;

import dev.notch.api.ingest.ImportRepository;
import dev.notch.api.search.IndexRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Polls the job queue and runs jobs, retrying failures up to {@link #MAX_ATTEMPTS} times. */
@Component
public class JobWorker {

    static final int MAX_ATTEMPTS = 3;
    private static final int JOBS_PER_POLL = 5;
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository jobs;
    private final ImportProcessor importProcessor;
    private final ImportRepository imports;
    private final IndexRegistry indexes;

    public JobWorker(JobRepository jobs, ImportProcessor importProcessor, ImportRepository imports,
                     IndexRegistry indexes) {
        this.jobs = jobs;
        this.importProcessor = importProcessor;
        this.imports = imports;
        this.indexes = indexes;
    }

    @Scheduled(fixedDelayString = "${notch.jobs.poll-delay-ms:2000}")
    public void poll() {
        jobs.requeueStale();
        for (int i = 0; i < JOBS_PER_POLL; i++) {
            Optional<Job> next = jobs.claimNext();
            if (next.isEmpty()) return;
            run(next.get());
        }
    }

    private void run(Job job) {
        long started = System.nanoTime();
        try {
            switch (job.kind()) {
                case ImportProcessor.KIND -> importProcessor.process(job);
                default -> throw new IllegalStateException("unknown job kind " + job.kind());
            }
            jobs.complete(job.id());
            indexes.contentChanged(job.projectId());  // new chunks and decisions become searchable, on every instance
            log.info("Job {} ({}) done in {} ms", job.id(), job.kind(), (System.nanoTime() - started) / 1_000_000);
        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (job.attempts() < MAX_ATTEMPTS) {
                log.warn("Job {} failed (attempt {}), retrying: {}", job.id(), job.attempts(), error);
                jobs.retryLater(job.id(), job.attempts(), error);
            } else {
                log.error("Job {} failed permanently: {}", job.id(), error, e);
                jobs.fail(job.id(), error);
                if (job.importId() != null) {
                    imports.markFailed(job.importId(), "Processing failed after " + MAX_ATTEMPTS + " attempts.");
                }
            }
        }
    }
}
