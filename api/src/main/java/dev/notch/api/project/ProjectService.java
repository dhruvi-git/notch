package dev.notch.api.project;

import dev.notch.api.config.ApiException;
import dev.notch.api.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final CurrentUser currentUser;

    public ProjectService(ProjectRepository projects, CurrentUser currentUser) {
        this.projects = projects;
        this.currentUser = currentUser;
    }

    public Project create(String name, boolean isPrivate) {
        String clean = name.strip().replaceAll("\\s+", " ");
        Project p = new Project(UUID.randomUUID(), currentUser.id(), clean, isPrivate, Instant.now());
        try {
            projects.insert(p);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("project_exists", "You already have a project called \"" + clean + "\".");
        }
        return p;
    }

    public List<Project> list() {
        return projects.list(currentUser.id());
    }

    /** The project, if it belongs to the current user; otherwise 404 (never 403, to avoid leaking ids). */
    public Project require(UUID projectId) {
        return projects.find(projectId, currentUser.id())
                .orElseThrow(() -> ApiException.notFound("Project"));
    }
}
