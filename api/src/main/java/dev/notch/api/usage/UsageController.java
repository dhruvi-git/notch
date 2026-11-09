package dev.notch.api.usage;

import dev.notch.api.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class UsageController {

    public record UsageResponse(Map<String, Integer> remaining, Map<String, Integer> limits) {
    }

    private final UsageService usage;
    private final CurrentUser currentUser;

    public UsageController(UsageService usage, CurrentUser currentUser) {
        this.usage = usage;
        this.currentUser = currentUser;
    }

    /** Powers the "24 of 30 questions left today" indicator. */
    @GetMapping("/api/usage")
    public UsageResponse usage() {
        return new UsageResponse(usage.remaining(currentUser.id()), Map.of(
                UsageService.IMPORT, usage.limit(UsageService.IMPORT),
                UsageService.QUESTION, usage.limit(UsageService.QUESTION)));
    }
}
