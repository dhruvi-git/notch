package dev.notch.api.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    public record CreateProjectRequest(@NotBlank @Size(max = 80) String name, Boolean isPrivate) {
    }

    public record ProjectResponse(UUID id, String name, boolean isPrivate, Instant createdAt) {
        static ProjectResponse of(Project p) {
            return new ProjectResponse(p.id(), p.name(), p.isPrivate(), p.createdAt());
        }
    }

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest req) {
        return ProjectResponse.of(service.create(req.name(), Boolean.TRUE.equals(req.isPrivate())));
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return service.list().stream().map(ProjectResponse::of).toList();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable UUID projectId) {
        return ProjectResponse.of(service.require(projectId));
    }
}
