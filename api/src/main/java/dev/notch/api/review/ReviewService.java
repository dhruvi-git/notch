package dev.notch.api.review;

import dev.notch.api.config.ApiException;
import dev.notch.api.crypto.ContentCipher;
import dev.notch.api.explain.CodeMapService;
import dev.notch.api.explain.core.CodeDecisionLinks;
import dev.notch.api.llm.CodeReviewRequest;
import dev.notch.api.llm.LlmClient;
import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectService;
import dev.notch.api.review.core.ReviewRules;
import dev.notch.api.review.core.ReviewSanitizer;
import dev.notch.api.review.core.ReviewSelection;
import dev.notch.api.security.CurrentUser;
import dev.notch.api.usage.UsageService;
import dev.notch.explain.CodeMap;
import dev.notch.explain.CodeMapBuilder;
import dev.notch.explain.SourceFile;
import dev.notch.explain.ZipSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Code reviews. A zip is reviewed here in the background: Notch's own checks always run, and for
 * a project that isn't private Claude reads the most important code too. The code stays in memory
 * only until the review is written, and only the report is stored. Claude Code sends reviews it
 * wrote itself (/notch review), which get the same checks from the project's code map.
 */
@Service
public class ReviewService {

    static final long MAX_ZIP_BYTES = 25L * 1024 * 1024;
    static final String CLAUDE_CODE = "claude-code";
    static final String ZIP = "zip";

    public record Summary(UUID id, String label, String source, String status, String error, int issues, int highIssues,
                          Instant createdAt, Instant finishedAt) {
    }

    public record Detail(UUID id, String label, String source, String status, String error, int issues, int highIssues,
                         Instant createdAt, Instant finishedAt, ReviewReport report) {
    }

    /** What Claude Code sends: the review in {@link ReviewReport}'s shape, as JSON. */
    public record Submission(String label, Map<String, Object> report, Integer filesReviewed) {
    }

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository repo;
    private final ProjectService projects;
    private final CurrentUser currentUser;
    private final ContentCipher cipher;
    private final ObjectMapper mapper;
    private final UsageService usage;
    private final CodeMapService codeMaps;
    private final LlmClient llm;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "code-review");
        t.setDaemon(true);
        return t;
    });

    public ReviewService(ReviewRepository repo, ProjectService projects, CurrentUser currentUser, ContentCipher cipher,
                         ObjectMapper mapper, UsageService usage, CodeMapService codeMaps, LlmClient llm) {
        this.repo = repo;
        this.projects = projects;
        this.currentUser = currentUser;
        this.cipher = cipher;
        this.mapper = mapper;
        this.usage = usage;
        this.codeMaps = codeMaps;
        this.llm = llm;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterrupted() {
        int n = repo.failInterrupted();
        if (n > 0) log.info("Marked {} code review(s) interrupted by the restart as failed", n);
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }

    // ------------------------------------------------------------------ zip

    public Summary startZip(UUID projectId, MultipartFile file) {
        Project project = projects.require(projectId);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("empty_file", "Choose a .zip of your code.");
        if (file.getSize() > MAX_ZIP_BYTES) {
            throw ApiException.tooLarge("The zip is over 25 MB. Leave out node_modules and build folders.");
        }
        ZipSource.Result read;
        try {
            read = ZipSource.read(file.getBytes());
        } catch (ZipSource.TooLargeException e) {
            throw ApiException.tooLarge(e.getMessage());
        } catch (IOException e) {
            throw ApiException.badRequest("invalid_zip", "That doesn't look like a valid zip file.");
        }
        CodeMap map = new CodeMapBuilder().build(read.files());
        if (map.files().isEmpty()) throw ApiException.badRequest("no_source", "The zip has no source files Notch can read.");

        UUID userId = currentUser.id();
        usage.consume(userId, UsageService.IMPORT);
        String name = file.getOriginalFilename() == null ? "code" : file.getOriginalFilename().replaceAll("(?i)\\.zip$", "");
        String label = label(name);
        try {
            codeMaps.storeReviewed(project, label, map);  // Explain shows the same code
        } catch (ApiException e) {
            log.info("Review {}: the map wasn't kept for Explain: {}", label, e.getMessage());
        }
        List<CodeDecisionLinks.Decision> decisions = codeMaps.decisions(project);
        Map<String, String> contents = new HashMap<>();
        java.util.Set<String> mapped = map.files().stream().map(CodeMap.FileInfo::path).collect(Collectors.toSet());
        for (SourceFile f : read.files()) if (mapped.contains(f.path())) contents.put(f.path(), f.content());

        UUID id = UUID.randomUUID();
        repo.insertPending(id, project.id(), userId, ZIP, label);
        workers.submit(() -> runZipReview(id, userId, project, label, map, contents, decisions));
        return new Summary(id, label, ZIP, "PENDING", null, 0, 0, Instant.now(), null);
    }

    private void runZipReview(UUID id, UUID userId, Project project, String label, CodeMap map, Map<String, String> contents,
                              List<CodeDecisionLinks.Decision> decisions) {
        long started = System.nanoTime();
        try {
            ReviewReport report = reviewZip(project, label, map, contents, decisions);
            store(id, userId, report);
            log.info("Review {} done in {} ms by {} ({} issues)", id, (System.nanoTime() - started) / 1_000_000,
                    report.coverage().reviewer(), report.issues().size());
        } catch (RuntimeException e) {
            log.error("Review {} failed", id, e);
            repo.fail(id, "The review didn't finish. Try again.");
        }
    }

    ReviewReport reviewZip(Project project, String label, CodeMap map, Map<String, String> contents,
                           List<CodeDecisionLinks.Decision> decisions) {
        List<ReviewReport.Issue> rules = ReviewRules.findings(map, contents, decisions);
        int codeFiles = (int) map.files().stream().filter(f -> isCode(f)).count();
        String note;
        if (project.isPrivate()) {
            note = "This project is private, so the code wasn't sent to a model. These findings come from Notch's automated checks.";
        } else {
            ReviewSelection.Selection sel = ReviewSelection.select(map, contents, ReviewSelection.DEFAULT_BUDGET_CHARS);
            List<CodeReviewRequest.File> files = sel.files().stream()
                    .map(p -> new CodeReviewRequest.File(p.path(), p.numbered(), p.truncated())).toList();
            List<String> decisionLines = decisions.stream().map(d -> d.title()
                    + (d.rejectedOptions().isEmpty() ? "" : " (ruled out: " + String.join(", ", d.rejectedOptions()) + ")")).toList();
            try {
                Map<String, Object> raw = files.isEmpty() ? null
                        : llm.reviewCode(new CodeReviewRequest(label, overview(map), decisionLines, files));
                if (raw != null) {
                    Map<String, Integer> known = new HashMap<>();
                    sel.files().forEach(p -> known.put(p.path(), p.lines()));
                    int total = sel.reviewableFiles();
                    String coverageNote = sel.files().size() < total
                            ? "Claude read the " + sel.files().size() + " most central files of " + total
                              + "; Notch's automated checks covered all of them."
                            : null;
                    ReviewReport.Coverage coverage = new ReviewReport.Coverage(llm.name(), total, sel.files().size(),
                            sel.linesSent(), coverageNote);
                    ReviewReport fromModel = ReviewSanitizer.clean(raw, known, "model", coverage);
                    return ReviewSanitizer.merge(fromModel, rules, ReviewRules.offlineReport(label, map, rules, decisions.size(), coverage));
                }
                note = "No AI model is set up, so these findings come from Notch's automated checks. "
                        + "Set ANTHROPIC_API_KEY on the server for a full review.";
            } catch (IllegalStateException e) {
                note = "Claude couldn't finish the review (" + clip(e.getMessage(), 160) + "), so these findings come from "
                        + "Notch's automated checks. Try again later.";
            }
        }
        ReviewReport.Coverage coverage = new ReviewReport.Coverage("automated checks", codeFiles, codeFiles,
                map.stats().lines(), note);
        return ReviewRules.offlineReport(label, map, rules, decisions.size(), coverage);
    }

    /** Facts from the map that help a reviewer see the whole: size, languages, modules, entry points. */
    static String overview(CodeMap map) {
        CodeMap.Stats s = map.stats();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ENGLISH, "%,d files, %,d of them code, %,d lines. Languages: ", s.files(), s.codeFiles(), s.lines()));
        sb.append(s.languages().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", "))).append(".\n");
        sb.append("Modules:\n");
        map.modules().stream().limit(25).forEach(m -> sb.append("- ").append(m.path().isEmpty() ? "(root)" : m.path())
                .append(": ").append(m.files()).append(" files, ").append(m.lines()).append(" lines")
                .append(m.dependsOn().isEmpty() ? "" : "; depends on " + String.join(", ", m.dependsOn())).append('\n'));
        if (!map.entryPoints().isEmpty()) {
            sb.append("Entry points:\n");
            for (String p : map.entryPoints().stream().limit(30).toList()) {
                CodeMap.FileInfo f = map.file(p);
                sb.append("- ").append(p);
                if (f != null && f.route() != null) sb.append(" (route ").append(f.route()).append(')');
                if (f != null && !f.endpoints().isEmpty()) sb.append(" endpoints: ").append(String.join(", ", f.endpoints().stream().limit(6).toList()));
                sb.append('\n');
            }
        }
        if (!map.startHere().isEmpty()) sb.append("Most depended-on files: ").append(String.join(", ", map.startHere())).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ Claude Code

    /** A review Claude Code wrote by reading the repository, with Notch's checks from the code map added. */
    public Summary submit(UUID projectId, Submission s) {
        Project project = projects.require(projectId);
        if (s == null || s.report() == null) throw ApiException.badRequest("invalid_review", "Send the review as a JSON object in \"report\".");
        UUID userId = currentUser.id();
        CodeMap map = codeMaps.latestMap(project).orElse(null);
        int codeFiles = map == null ? 0 : (int) map.files().stream().filter(ReviewSelection::reviewable).count();
        int reviewed = s.filesReviewed() == null ? 0 : Math.max(0, s.filesReviewed());
        ReviewReport.Coverage coverage = new ReviewReport.Coverage("Claude Code", codeFiles, reviewed, 0, null);
        ReviewReport report = ReviewSanitizer.clean(s.report(), null, "agent", coverage);
        if (report.summary() == null) throw ApiException.badRequest("invalid_review", "The review needs a \"summary\".");
        if (map != null) {
            List<CodeDecisionLinks.Decision> decisions = codeMaps.decisions(project);
            List<ReviewReport.Issue> rules = ReviewRules.findings(map, Map.of(), decisions);
            report = ReviewSanitizer.merge(report, rules, ReviewRules.offlineReport(project.name(), map, rules, decisions.size(), coverage));
        }
        usage.consume(userId, UsageService.CAPTURE);
        UUID id = UUID.randomUUID();
        String label = label(s.label() == null || s.label().isBlank() ? project.name() : s.label());
        repo.insertDone(id, project.id(), userId, CLAUDE_CODE, label, report.issues().size(), (int) report.highIssues(), seal(report, userId));
        return new Summary(id, label, CLAUDE_CODE, "DONE", null, report.issues().size(), (int) report.highIssues(), Instant.now(), Instant.now());
    }

    // ------------------------------------------------------------------ reading

    public List<Summary> list(UUID projectId) {
        Project project = projects.require(projectId);
        return repo.list(project.id(), currentUser.id()).stream().map(ReviewService::summary).toList();
    }

    public Detail get(UUID projectId, UUID reviewId) {
        Project project = projects.require(projectId);
        UUID userId = currentUser.id();
        ReviewRepository.Stored s = repo.get(reviewId, project.id(), userId).orElseThrow(() -> ApiException.notFound("Review"));
        ReviewReport report = s.payload() == null ? null
                : mapper.readValue(cipher.decrypt(s.payload(), userId.toString()).getBytes(StandardCharsets.UTF_8), ReviewReport.class);
        return new Detail(s.id(), s.label(), s.source(), s.status(), s.error(), s.issues(), s.highIssues(), s.createdAt(),
                s.finishedAt(), report);
    }

    public void delete(UUID projectId, UUID reviewId) {
        Project project = projects.require(projectId);
        if (!repo.delete(reviewId, project.id(), currentUser.id())) throw ApiException.notFound("Review");
    }

    // ------------------------------------------------------------------ helpers

    private void store(UUID id, UUID userId, ReviewReport report) {
        repo.finish(id, report.issues().size(), (int) report.highIssues(), seal(report, userId));
    }

    private byte[] seal(ReviewReport report, UUID userId) {
        return cipher.encrypt(new String(mapper.writeValueAsBytes(report), StandardCharsets.UTF_8), userId.toString());
    }

    private static Summary summary(ReviewRepository.Stored s) {
        return new Summary(s.id(), s.label(), s.source(), s.status(), s.error(), s.issues(), s.highIssues(), s.createdAt(), s.finishedAt());
    }

    private static boolean isCode(CodeMap.FileInfo f) {
        return switch (f.role()) {
            case TEST, CONFIG, MIGRATION, STYLE, DOC, DATA -> false;
            default -> true;
        };
    }

    private static String label(String s) {
        String t = s.strip();
        if (t.isEmpty()) return "codebase";
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    private static String clip(String s, int max) {
        if (s == null) return "no reason given";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
