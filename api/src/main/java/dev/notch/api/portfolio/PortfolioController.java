package dev.notch.api.portfolio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The candidate's portfolio, skill autocomplete, and public profiles (no sign-in, under /public). */
@RestController
public class PortfolioController {

    public record SaveProfileRequest(@NotBlank @Size(max = 39) String handle, @Size(max = 120) String headline,
                                     @Size(max = 60) String domain, boolean isPublic) {
    }

    public record AddProjectRequest(@NotBlank @Size(max = 120) String title, @Size(max = 500) String url,
                                    @Size(max = 2000) String description,
                                    @Size(max = 20) List<@Size(max = 60) String> skills, LocalDate lastActive) {
    }

    public record SkillPreference(boolean claimed, boolean hidden) {
    }

    public record LinkApproval(@NotNull Boolean approved) {
    }

    private final PortfolioService portfolio;

    public PortfolioController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @GetMapping("/api/portfolio/me")
    public ProfileView mine() {
        return portfolio.mine();
    }

    @PutMapping("/api/portfolio/me")
    public ProfileView save(@Valid @RequestBody SaveProfileRequest req) {
        return portfolio.saveProfile(req.handle(), req.headline(), req.domain(), req.isPublic());
    }

    @PostMapping("/api/portfolio/evidence")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileView.EvidenceView addProject(@Valid @RequestBody AddProjectRequest req) {
        return portfolio.addProject(req.title(), req.url(), req.description(), req.skills(), req.lastActive());
    }

    @DeleteMapping("/api/portfolio/evidence/{evidenceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvidence(@PathVariable UUID evidenceId) {
        portfolio.deleteEvidence(evidenceId);
    }

    @PutMapping("/api/portfolio/skills/{skillId}")
    public ProfileView setSkill(@PathVariable int skillId, @RequestBody SkillPreference req) {
        return portfolio.setSkill(skillId, req.claimed(), req.hidden());
    }

    @PatchMapping("/api/portfolio/evidence/{evidenceId}/skills/{skillId}")
    public ProfileView approve(@PathVariable UUID evidenceId, @PathVariable int skillId, @Valid @RequestBody LinkApproval req) {
        return portfolio.setLink(evidenceId, skillId, req.approved());
    }

    @GetMapping("/api/skills/autocomplete")
    public List<ProfileView.SkillRef> autocomplete(@RequestParam(defaultValue = "") String q) {
        return portfolio.autocomplete(q);
    }

    @GetMapping("/public/profiles/{handle}")
    public ProfileView publicProfile(@PathVariable String handle) {
        return portfolio.publicProfile(handle);
    }
}
