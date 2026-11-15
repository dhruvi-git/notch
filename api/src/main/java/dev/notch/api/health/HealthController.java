package dev.notch.api.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The project's health check and recap (see {@link HealthService}). */
@RestController
public class HealthController {

    private final HealthService service;

    public HealthController(HealthService service) {
        this.service = service;
    }

    @GetMapping("/api/projects/{projectId}/health")
    public HealthService.Health health(@PathVariable UUID projectId) {
        return service.check(projectId);
    }
}
