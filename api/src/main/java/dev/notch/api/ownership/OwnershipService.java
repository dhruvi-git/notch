package dev.notch.api.ownership;

import dev.notch.api.config.ApiException;
import dev.notch.api.llm.LlmClient;
import dev.notch.api.llm.OwnershipGradeRequest;
import dev.notch.api.llm.OwnershipPrompt;
import dev.notch.api.ownership.core.AnswerGrader;
import dev.notch.api.ownership.core.QuestionPlanner;
import dev.notch.api.ownership.core.RegionExtractor;
import dev.notch.api.portfolio.CandidatePool;
import dev.notch.api.portfolio.CatalogService;
import dev.notch.api.portfolio.GitHubRepoClient;
import dev.notch.api.portfolio.PortfolioRepository;
import dev.notch.api.security.CurrentUser;
import dev.notch.engine.score.SkillScoring;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ownership Checks: a timed session in which a candidate explains their own code.
 *
 * <p>Starting a check reads the repository from GitHub, splits it into regions, and chooses what
 * to ask with greedy set cover so the questions touch every skill, concept and main file the
 * repository shows. A language model writes and grades the questions when one is configured;
 * otherwise concept templates and a transparent rubric do. Passing makes the repository
 * <em>defended</em>, which raises its skills to the Defended level and lifts the score cap.
 */
@Service
public class OwnershipService {

    public static final Duration LENGTH = Duration.ofMinutes(12);
    static final Duration GRACE = Duration.ofSeconds(30);
    static final Duration RETAKE_AFTER = Duration.ofHours(24);
    static final int MAX_ANSWER_CHARS = 5000;
    static final int FILES_WITH_TOKEN = 20;
    static final int FILES_WITHOUT_TOKEN = 8;

    public record AnswerInput(String answer, long millis, int pastes, int focusLost) {
    }

    private final OwnershipRepository repo;
    private final PortfolioRepository portfolio;
    private final CatalogService catalog;
    private final CandidatePool pool;
    private final GitHubRepoClient github;
    private final LlmClient llm;
    private final CurrentUser currentUser;
    private final Set<UUID> starting = ConcurrentHashMap.newKeySet();

    public OwnershipService(OwnershipRepository repo, PortfolioRepository portfolio, CatalogService catalog, CandidatePool pool,
                            GitHubRepoClient github, LlmClient llm, CurrentUser currentUser) {
        this.repo = repo;
        this.portfolio = portfolio;
        this.catalog = catalog;
        this.pool = pool;
        this.github = github;
        this.llm = llm;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ starting

    public CheckView start(UUID evidenceId) {
        UUID me = currentUser.id();
        PortfolioRepository.StoredEvidence ev = portfolio.evidence(me).stream().filter(e -> e.id().equals(evidenceId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("That repository"));
        if (ev.kind() != SkillScoring.Kind.GITHUB_REPO || ev.url() == null) {
            throw ApiException.badRequest("not_a_repository", "Ownership Checks are about code: pick one of your GitHub repositories.");
        }
        if (ev.duplicateOf() != null) {
            throw ApiException.badRequest("duplicate_repository", "This repository matches one someone else claimed first, so it can't be defended.");
        }
        Optional<OwnershipRepository.CheckRow> open = repo.openCheck(me, evidenceId);
        if (open.isPresent()) {
            if (Instant.now().isBefore(open.get().deadline().plus(GRACE))) return view(open.get(), null);
            finish(open.get()); // an abandoned check finishes with what was answered
        }
        Optional<Instant> last = repo.lastFinished(me, evidenceId);
        if (last.isPresent() && last.get().plus(RETAKE_AFTER).isAfter(Instant.now())) {
            throw ApiException.conflict("retake_later", "You can take another check on this repository 24 hours after the last one.");
        }
        if (!starting.add(me)) throw ApiException.conflict("check_starting", "A check is already being prepared. Give it a moment.");
        try {
            return create(me, ev);
        } finally {
            starting.remove(me);
        }
    }

    private CheckView create(UUID me, PortfolioRepository.StoredEvidence ev) {
        String fullName = fullName(ev.url());
        List<RegionExtractor.SourceFile> files;
        try {
            files = github.sourceFiles(fullName, github.authenticated() ? FILES_WITH_TOKEN : FILES_WITHOUT_TOKEN);
        } catch (GitHubRepoClient.RepoNotFound e) {
            throw ApiException.notFoundWith("repository_not_found", "GitHub has no public repository " + fullName + " any more.");
        } catch (GitHubRepoClient.RateLimited e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "github_rate_limited",
                    "GitHub's hourly limit for this server was reached. Try again in an hour.");
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "github_unavailable", "GitHub couldn't be reached. Try again shortly.");
        }
        List<RegionExtractor.Region> regions = RegionExtractor.extract(files, catalog.catalog());
        Set<Integer> focus = new HashSet<>();
        ev.skills().forEach((id, approved) -> {
            if (approved) focus.add(id);
        });
        QuestionPlanner.Plan plan = QuestionPlanner.plan(regions, focus, catalog.catalog(), QuestionPlanner.DEFAULT_QUESTIONS);
        if (plan.questions().isEmpty()) {
            throw new ApiException(HttpStatus.valueOf(422), "no_code", "Notch couldn't find source code to ask about in " + fullName + ".");
        }

        List<OwnershipRepository.NewQuestion> questions = new ArrayList<>();
        int byModel = 0;
        for (int i = 0; i < plan.questions().size(); i++) {
            QuestionPlanner.Planned p = plan.questions().get(i);
            RegionExtractor.Region r = p.region();
            String prompt = p.prompt();
            List<List<String>> rubric = p.rubric();
            Optional<Written> written = modelQuestion(p);
            if (written.isPresent()) {
                prompt = written.get().prompt();
                rubric = written.get().rubric();
                byModel++;
            }
            questions.add(new OwnershipRepository.NewQuestion(i, r.path(), r.startLine(), r.endLine(), r.language(), r.code(),
                    p.concept() == null ? null : p.concept().label(), prompt, rubric, p.identifiers(), p.covers()));
        }
        UUID id = UUID.randomUUID();
        Instant deadline = Instant.now().plus(LENGTH);
        repo.create(id, me, ev.id(), deadline, byModel == questions.size() ? "model" : "templates", plan.elements(),
                plan.covered(), questions);
        return view(repo.check(me, id).orElseThrow(), null);
    }

    private record Written(String prompt, List<List<String>> rubric) {
    }

    /** The model's question, if a model is configured and its reply is usable. */
    private Optional<Written> modelQuestion(QuestionPlanner.Planned p) {
        RegionExtractor.Region r = p.region();
        Map<String, Object> reply;
        try {
            reply = llm.ownershipQuestion(new OwnershipPrompt(r.path(), r.startLine(), r.endLine(), r.language(), r.code(),
                    p.concept() == null ? null : p.concept().label(), p.prompt()));
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
        if (reply == null || !(reply.get("question") instanceof String q) || q.length() < 20 || q.length() > 600) return Optional.empty();
        List<List<String>> rubric = new ArrayList<>();
        if (reply.get("rubric") instanceof List<?> groups) {
            for (Object g : groups) {
                if (!(g instanceof List<?> terms)) continue;
                List<String> clean = terms.stream().filter(t -> t instanceof String s && !s.isBlank() && s.length() <= 40)
                        .map(t -> ((String) t).strip().toLowerCase(java.util.Locale.ROOT).replace("|", " ")).limit(6).toList();
                if (!clean.isEmpty()) rubric.add(clean);
            }
        }
        if (rubric.size() < 2) return Optional.empty();
        return Optional.of(new Written(q.strip(), rubric.subList(0, Math.min(5, rubric.size()))));
    }

    // ------------------------------------------------------------------ answering

    public CheckView get(UUID checkId) {
        OwnershipRepository.CheckRow c = own(checkId);
        if (c.open() && Instant.now().isAfter(c.deadline().plus(GRACE))) c = finish(c);
        return view(c, null);
    }

    public CheckView answer(UUID checkId, int position, AnswerInput in) {
        OwnershipRepository.CheckRow c = own(checkId);
        if (!c.open()) throw ApiException.conflict("check_finished", "This check is finished; answers can't change.");
        if (Instant.now().isAfter(c.deadline().plus(GRACE))) {
            finish(c);
            throw ApiException.conflict("time_up", "Time is up. The check was finished with the answers already saved.");
        }
        String answer = in.answer() == null ? "" : in.answer();
        if (answer.length() > MAX_ANSWER_CHARS) {
            throw ApiException.badRequest("answer_too_long", "Answers can be up to " + MAX_ANSWER_CHARS + " characters.");
        }
        if (in.millis() < 0 || in.pastes() < 0 || in.focusLost() < 0) {
            throw ApiException.badRequest("invalid_answer", "Timing and counts can't be negative.");
        }
        if (!repo.saveAnswer(checkId, position, answer.strip(), in.millis(), in.pastes(), in.focusLost())) {
            throw ApiException.notFound("That question");
        }
        return view(c, null);
    }

    public CheckView finish(UUID checkId) {
        OwnershipRepository.CheckRow c = own(checkId);
        return view(c.open() ? finish(c) : c, null);
    }

    /** Grades every answer, decides the verdict and closes the check. */
    private OwnershipRepository.CheckRow finish(OwnershipRepository.CheckRow c) {
        List<OwnershipRepository.QuestionRow> qs = repo.questions(c.id());
        List<OwnershipRepository.GradedQuestion> graded = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        int pasted = 0, byModel = 0, byRubric = 0;
        for (OwnershipRepository.QuestionRow q : qs) {
            String a = q.answer();
            if (a == null || a.isBlank()) {
                graded.add(new OwnershipRepository.GradedQuestion(q.id(), 0, "Not answered.", List.of()));
                continue;
            }
            List<String> flags = AnswerGrader.integrityFlags(a, q.millis() == null ? 0 : q.millis(), q.pastes(), q.focusLost());
            if (q.pastes() > 0) pasted++;
            Optional<AnswerGrader.Grade> model = modelGrade(q);
            AnswerGrader.Grade g = model.orElseGet(() -> AnswerGrader.grade(q.rubric(), q.identifiers(), a));
            if (model.isPresent()) byModel++;
            else byRubric++;
            scores.add(g.score());
            graded.add(new OwnershipRepository.GradedQuestion(q.id(), g.score(), g.feedback(), flags));
        }
        AnswerGrader.Verdict v = AnswerGrader.verdict(scores, qs.size(), pasted);
        String by = byModel > 0 && byRubric > 0 ? "mixed" : byModel > 0 ? "model" : "rubric";
        repo.finish(c.id(), v.score(), v.defended(), v.summary(), by, graded);
        pool.invalidate();
        return repo.check(c.userId(), c.id()).orElseThrow();
    }

    private Optional<AnswerGrader.Grade> modelGrade(OwnershipRepository.QuestionRow q) {
        Map<String, Object> reply;
        try {
            reply = llm.gradeOwnership(new OwnershipGradeRequest(q.path(), q.code(), q.prompt(),
                    q.rubric().stream().map(g -> String.join(" / ", g)).toList(), q.answer()));
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
        if (reply == null || !(reply.get("score") instanceof Number n)) return Optional.empty();
        int score = (int) Math.max(0, Math.min(100, Math.round(n.doubleValue())));
        String feedback = reply.get("feedback") instanceof String f ? f.strip() : "";
        if (feedback.length() > 500) feedback = feedback.substring(0, 499) + "…";
        return Optional.of(new AnswerGrader.Grade(score, strings(reply.get("covered")), strings(reply.get("missed")), feedback));
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().filter(x -> x instanceof String).map(x -> (String) x).limit(6).toList();
    }

    // ------------------------------------------------------------------ public transcript

    public CheckView publicTranscript(UUID checkId) {
        OwnershipRepository.CheckRow c = repo.publicCheck(checkId)
                .orElseThrow(() -> ApiException.notFound("A public Ownership Check"));
        return view(c, repo.header(checkId));
    }

    // ------------------------------------------------------------------ helpers

    private OwnershipRepository.CheckRow own(UUID checkId) {
        return repo.check(currentUser.id(), checkId).orElseThrow(() -> ApiException.notFound("That Ownership Check"));
    }

    private CheckView view(OwnershipRepository.CheckRow c, OwnershipRepository.PublicHeader header) {
        OwnershipRepository.PublicHeader h = header != null ? header : repo.header(c.id());
        boolean done = !c.open();
        List<CheckView.Question> qs = new ArrayList<>();
        for (OwnershipRepository.QuestionRow q : repo.questions(c.id())) {
            qs.add(new CheckView.Question(q.position(), q.path(), q.startLine(), q.endLine(), q.language(), q.code(), q.concept(),
                    q.prompt(), q.covers(), q.answer(), q.millis(), done ? q.score() : null, done ? q.feedback() : null,
                    done ? q.flags() : List.of()));
        }
        long left = done ? 0 : Math.max(0, Duration.between(Instant.now(), c.deadline()).toSeconds());
        return new CheckView(c.id(), c.evidenceId(), header != null ? h.handle() : null, h.repoTitle(), h.repoUrl(), c.status(),
                c.startedAt(), c.deadline(), c.finishedAt(), left, c.score(), c.defended(), c.summary(), c.questionsBy(),
                c.gradedBy(), c.planElements(), c.planCovered(), qs);
    }

    /** "owner/name" from a GitHub repository URL. */
    static String fullName(String url) {
        URI u = URI.create(url);
        String path = u.getPath() == null ? "" : u.getPath().replaceAll("^/+|/+$", "").replaceAll("\\.git$", "");
        if (!"github.com".equalsIgnoreCase(u.getHost()) || path.split("/").length != 2) {
            throw ApiException.badRequest("not_a_github_repository", "This evidence doesn't point at a GitHub repository.");
        }
        return path;
    }
}
