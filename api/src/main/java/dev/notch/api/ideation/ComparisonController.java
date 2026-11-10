package dev.notch.api.ideation;

import dev.notch.api.ideation.core.Comparison;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The ideation workbench: compare options, then decide. */
@RestController
public class ComparisonController {

    public record CreateRequest(@NotBlank @Size(max = 200) String question,
                                @Size(max = 8) List<@Size(max = 60) String> options) {
    }

    public record DecideRequest(@NotBlank String optionId, @Size(max = 300) String title, @Size(max = 500) String rationale) {
    }

    private final ComparisonService service;

    public ComparisonController(ComparisonService service) {
        this.service = service;
    }

    @GetMapping("/api/projects/{projectId}/comparisons")
    public List<ComparisonService.Summary> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    @PostMapping("/api/projects/{projectId}/comparisons")
    @ResponseStatus(HttpStatus.CREATED)
    public ComparisonService.View create(@PathVariable UUID projectId, @Valid @RequestBody CreateRequest req) {
        return service.create(projectId, req.question(), req.options());
    }

    @GetMapping("/api/comparisons/{id}")
    public ComparisonService.View get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Autosave: send the whole matrix, get back the saved matrix and a fresh analysis. */
    @PutMapping("/api/comparisons/{id}")
    public ComparisonService.View save(@PathVariable UUID id, @RequestBody Comparison comparison) {
        return service.save(id, comparison);
    }

    @PostMapping("/api/comparisons/{id}/decide")
    public Map<String, Object> decide(@PathVariable UUID id, @Valid @RequestBody DecideRequest req) {
        return service.decide(id, req.optionId(), req.title(), req.rationale());
    }

    @DeleteMapping("/api/comparisons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
