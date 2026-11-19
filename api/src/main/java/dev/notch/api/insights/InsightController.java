package dev.notch.api.insights;

import dev.notch.api.insights.core.MemoryImport;
import dev.notch.api.insights.core.Read;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The candidate's read, their goal, and the memory import; the public read of a public profile. */
@RestController
public class InsightController {

    public record MemoryReply(@NotBlank @Size(max = MemoryImport.MAX_TEXT) String text) {
    }

    public record GoalRequest(@NotBlank @Size(max = 80) String goal) {
    }

    private final InsightService insights;

    public InsightController(InsightService insights) {
        this.insights = insights;
    }

    @GetMapping("/api/insights/me")
    public InsightService.MyRead mine() {
        return insights.mine();
    }

    @PutMapping("/api/insights/goal")
    public InsightService.MyRead setGoal(@Valid @RequestBody GoalRequest req) {
        return insights.setGoal(req.goal());
    }

    @GetMapping("/api/portfolio/memory-prompt")
    public Map<String, String> prompt() {
        return Map.of("prompt", insights.prompt());
    }

    @PostMapping("/api/portfolio/memory-import")
    public InsightService.MemoryImportResult importMemory(@Valid @RequestBody MemoryReply req) {
        return insights.importMemory(req.text());
    }

    @GetMapping("/public/profiles/{handle}/read")
    public Read publicRead(@PathVariable String handle) {
        return insights.publicRead(handle);
    }
}
