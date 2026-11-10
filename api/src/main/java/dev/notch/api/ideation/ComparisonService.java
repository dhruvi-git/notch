package dev.notch.api.ideation;

import dev.notch.api.config.ApiException;
import dev.notch.api.crypto.ContentCipher;
import dev.notch.api.decisions.DecisionService;
import dev.notch.api.decisions.MemoryView;
import dev.notch.api.ideation.core.Analysis;
import dev.notch.api.ideation.core.Comparison;
import dev.notch.api.ideation.core.ComparisonAnalyzer;
import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectService;
import dev.notch.api.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ideation workbench: create a comparison, edit it (every save returns a fresh analysis),
 * and decide, which records the winner as a decision with the other options ruled out.
 */
@Service
public class ComparisonService {

    public record View(UUID id, UUID projectId, String status, Comparison comparison, Analysis analysis,
                       UUID decisionId, String chosen, Instant createdAt, Instant updatedAt) {
    }

    public record Summary(UUID id, String question, String status, List<String> options, String leader,
                          String chosen, Instant updatedAt) {
    }

    private final ComparisonRepository repo;
    private final ProjectService projects;
    private final CurrentUser currentUser;
    private final ContentCipher cipher;
    private final ObjectMapper mapper;
    private final DecisionService decisions;
    /** Stored as the decision's body, so the Ledger can say where it came from. */
    public static final String FROM_COMPARISON = "Decided in Compare.";

    private final ComparisonAnalyzer analyzer = new ComparisonAnalyzer();

    public ComparisonService(ComparisonRepository repo, ProjectService projects, CurrentUser currentUser,
                             ContentCipher cipher, ObjectMapper mapper, DecisionService decisions) {
        this.repo = repo;
        this.projects = projects;
        this.currentUser = currentUser;
        this.cipher = cipher;
        this.mapper = mapper;
        this.decisions = decisions;
    }

    public View create(UUID projectId, String question, List<String> optionNames) {
        Project project = projects.require(projectId);
        List<Comparison.Option> options = new ArrayList<>();
        int n = 1;
        for (String name : optionNames == null ? List.<String>of() : optionNames) {
            if (name != null && !name.isBlank()) options.add(new Comparison.Option("o" + n++, name.strip(), null));
        }
        Comparison c = new Comparison(question, options, ComparisonAnalyzer.defaultCriteria(), Map.of());
        List<String> problems = ComparisonAnalyzer.validate(c);
        if (problems.stream().anyMatch(p -> !p.startsWith("Add at least two options"))) {
            throw ApiException.badRequest("invalid_comparison", problems.get(0));
        }
        UUID id = UUID.randomUUID();
        UUID userId = currentUser.id();
        repo.insert(id, project.id(), userId, seal(c, userId));
        return view(require(id));
    }

    public View get(UUID id) {
        return view(require(id));
    }

    public List<Summary> list(UUID projectId) {
        Project project = projects.require(projectId);
        UUID userId = currentUser.id();
        return repo.list(project.id(), userId).stream().map(s -> {
            Comparison c = open(s.payload(), userId);
            Analysis a = analyzer.analyze(c);
            String leader = a.winnerIds().size() == 1 ? a.standings().get(0).name() : null;
            return new Summary(s.id(), c.question(), s.status(), c.options().stream().map(Comparison.Option::name).toList(),
                    leader, s.chosen(), s.updatedAt());
        }).toList();
    }

    /** Saves the matrix (autosave from the editor). Invalid matrices are saved too; the analysis says what's wrong. */
    public View save(UUID id, Comparison c) {
        ComparisonRepository.Stored s = require(id);
        if (!s.status().equals("OPEN")) {
            throw ApiException.conflict("already_decided", "This comparison is decided. Start a new one to revisit the choice.");
        }
        if (c.options().size() > Comparison.MAX_OPTIONS || c.criteria().size() > Comparison.MAX_CRITERIA) {
            throw ApiException.badRequest("too_large", "Compare at most 8 options on 12 criteria.");
        }
        UUID userId = currentUser.id();
        if (!repo.updateOpen(id, userId, seal(c, userId))) {
            throw ApiException.conflict("already_decided", "This comparison was decided meanwhile.");
        }
        return view(require(id));
    }

    /**
     * Records the chosen option as a decision. Every other option becomes "ruled out", so the
     * guard and drift detection protect the choice from then on.
     */
    @Transactional
    public Map<String, Object> decide(UUID id, String optionId, String title, String rationale) {
        ComparisonRepository.Stored s = require(id);
        if (!s.status().equals("OPEN")) throw ApiException.conflict("already_decided", "This comparison is already decided.");
        UUID userId = currentUser.id();
        Comparison c = open(s.payload(), userId);
        List<String> problems = ComparisonAnalyzer.validate(c);
        if (!problems.isEmpty()) throw ApiException.badRequest("invalid_comparison", problems.get(0));
        Comparison.Option chosen = c.options().stream().filter(o -> o.id().equals(optionId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("unknown_option", "Choose one of the options in this comparison."));
        List<String> ruledOut = c.options().stream().filter(o -> o != chosen).map(Comparison.Option::name).toList();
        String decisionTitle = title == null || title.isBlank()
                ? "For \u201c" + c.question() + "\u201d, we chose " + chosen.name() + "." : title.strip();
        MemoryView decision = decisions.create(s.projectId(), "DECISION", decisionTitle, FROM_COMPARISON, rationale, ruledOut);
        if (!repo.markDecided(id, userId, decision.id(), chosen.name())) {
            throw ApiException.conflict("already_decided", "This comparison was decided meanwhile.");
        }
        return Map.of("comparison", view(require(id)), "decision", decision);
    }

    public void delete(UUID id) {
        if (!repo.delete(require(id).id(), currentUser.id())) throw ApiException.notFound("Comparison");
    }

    private ComparisonRepository.Stored require(UUID id) {
        return repo.find(id, currentUser.id()).orElseThrow(() -> ApiException.notFound("Comparison"));
    }

    private View view(ComparisonRepository.Stored s) {
        Comparison c = open(s.payload(), currentUser.id());
        return new View(s.id(), s.projectId(), s.status(), c, analyzer.analyze(c), s.decisionId(), s.chosen(),
                s.createdAt(), s.updatedAt());
    }

    private byte[] seal(Comparison c, UUID userId) {
        return cipher.encrypt(new String(mapper.writeValueAsBytes(c), StandardCharsets.UTF_8), userId.toString());
    }

    private Comparison open(byte[] payload, UUID userId) {
        return mapper.readValue(cipher.decrypt(payload, userId.toString()).getBytes(StandardCharsets.UTF_8), Comparison.class);
    }
}
