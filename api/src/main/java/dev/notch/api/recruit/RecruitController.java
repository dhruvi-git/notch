package dev.notch.api.recruit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Hiring drives: create from a job description, edit roles, shortlist, assign seats. */
@RestController
public class RecruitController {

    public record CreateDriveRequest(@NotBlank @Size(max = 120) String title,
                                     @NotBlank @Size(max = 20_000) String description) {
    }

    public record SaveRolesRequest(@NotNull @Size(min = 1, max = 10) List<RecruitService.RoleInput> roles) {
    }

    private final RecruitService recruit;

    public RecruitController(RecruitService recruit) {
        this.recruit = recruit;
    }

    @PostMapping("/api/drives")
    @ResponseStatus(HttpStatus.CREATED)
    public DriveView.Drive create(@Valid @RequestBody CreateDriveRequest req) {
        return recruit.create(req.title(), req.description());
    }

    @GetMapping("/api/drives")
    public List<DriveView.Summary> list() {
        return recruit.list();
    }

    @GetMapping("/api/drives/{driveId}")
    public DriveView.Drive get(@PathVariable UUID driveId) {
        return recruit.get(driveId);
    }

    @DeleteMapping("/api/drives/{driveId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID driveId) {
        recruit.delete(driveId);
    }

    @PutMapping("/api/drives/{driveId}/roles")
    public DriveView.Drive saveRoles(@PathVariable UUID driveId, @Valid @RequestBody SaveRolesRequest req) {
        return recruit.saveRoles(driveId, req.roles());
    }

    @GetMapping("/api/drives/{driveId}/roles/{roleId}/shortlist")
    public DriveView.Shortlist shortlist(@PathVariable UUID driveId, @PathVariable UUID roleId,
                                         @RequestParam(defaultValue = "20") int k) {
        return recruit.shortlist(driveId, roleId, k);
    }

    @PostMapping("/api/drives/{driveId}/assign")
    public DriveView.Assignment assign(@PathVariable UUID driveId) {
        return recruit.assign(driveId);
    }

    @GetMapping("/api/drives/{driveId}/assignment")
    public DriveView.Assignment latestAssignment(@PathVariable UUID driveId) {
        return recruit.latestAssignment(driveId);
    }
}
