package dev.notch.api.decisions;

import dev.notch.api.decisions.core.ContextPackBuilder;
import dev.notch.api.decisions.core.DriftAlert;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Capture, Sync and Guard: the decision ledger, agent files and drift alerts. */
@RestController
public class DecisionController {

    public record CreateMemoryRequest(@NotBlank String kind, @NotBlank @Size(max = 300) String title,
                                      @Size(max = 2000) String body, @Size(max = 500) String rationale,
                                      @Size(max = 10) List<@Size(max = 80) String> rejectedOptions) {
    }

    public record StatusRequest(@NotBlank String status) {
    }

    /** Either {@code replacementId}, or the text of a new decision. */
    public record SupersedeRequest(UUID replacementId, @Size(max = 300) String title,
                                   @Size(max = 500) String rationale,
                                   @Size(max = 10) List<@Size(max = 80) String> rejectedOptions) {
    }

    private final DecisionService decisions;
    private final DriftService drift;
    private final ResumeService resume;

    public DecisionController(DecisionService decisions, DriftService drift, ResumeService resume) {
        this.decisions = decisions;
        this.drift = drift;
        this.resume = resume;
    }

    @PostMapping("/api/projects/{projectId}/memories")
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryView create(@PathVariable UUID projectId, @Valid @RequestBody CreateMemoryRequest req) {
        return decisions.create(projectId, req.kind(), req.title(), req.body(), req.rationale(), req.rejectedOptions());
    }

    @PatchMapping("/api/memories/{memoryId}")
    public MemoryView setStatus(@PathVariable UUID memoryId, @Valid @RequestBody StatusRequest req) {
        return decisions.setStatus(memoryId, req.status());
    }

    /** Replaces a decision. Returns the replacement. */
    @PostMapping("/api/memories/{memoryId}/supersede")
    public MemoryView supersede(@PathVariable UUID memoryId, @Valid @RequestBody SupersedeRequest req) {
        return decisions.supersede(memoryId, req.replacementId(), req.title(), req.rationale(), req.rejectedOptions());
    }

    @GetMapping("/api/memories/{memoryId}/history")
    public List<MemoryView> history(@PathVariable UUID memoryId) {
        return decisions.history(memoryId);
    }

    @GetMapping("/api/projects/{projectId}/drift")
    public List<DriftAlert> drift(@PathVariable UUID projectId) {
        return drift.alerts(projectId);
    }

    @PostMapping("/api/projects/{projectId}/drift/{alertKey}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable UUID projectId, @PathVariable String alertKey) {
        drift.dismiss(projectId, alertKey);
    }

    @GetMapping("/api/projects/{projectId}/resume")
    public ResumeService.Resume resume(@PathVariable UUID projectId) {
        return resume.resume(projectId);
    }

    /** A Markdown briefing to paste into a new chat with any assistant. */
    @GetMapping("/api/projects/{projectId}/context-pack")
    public Map<String, Object> contextPack(@PathVariable UUID projectId,
                                           @RequestParam(defaultValue = "1500") int budget) {
        int b = Math.max(200, Math.min(budget, 8000));
        String md = resume.contextPack(projectId, ContextPackBuilder.Format.CONTEXT_PACK, b);
        return Map.of("markdown", md, "estimatedTokens", (md.length() + 3) / 4, "budget", b);
    }

    @GetMapping("/api/projects/{projectId}/export/agents-md")
    public ResponseEntity<String> agentsMd(@PathVariable UUID projectId) {
        return file("AGENTS.md", resume.contextPack(projectId, ContextPackBuilder.Format.AGENTS_MD, 3000));
    }

    @GetMapping("/api/projects/{projectId}/export/claude-md")
    public ResponseEntity<String> claudeMd(@PathVariable UUID projectId) {
        return file("CLAUDE.md", resume.contextPack(projectId, ContextPackBuilder.Format.CLAUDE_MD, 3000));
    }

    private static ResponseEntity<String> file(String name, String content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(content);
    }
}
