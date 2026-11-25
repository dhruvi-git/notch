package dev.notch.api.outreach;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Recruiters contacting candidates from a drive, and candidates answering. */
@RestController
public class OutreachController {

    public record DraftRequest(@NotNull UUID candidateId, UUID roleId) {
    }

    public record SendRequest(@NotNull UUID candidateId, UUID roleId, @NotBlank @Size(max = 200) String subject,
                              @NotBlank @Size(max = 5000) String body) {
    }

    public record RespondRequest(@NotNull Boolean interested) {
    }

    private final OutreachService outreach;

    public OutreachController(OutreachService outreach) {
        this.outreach = outreach;
    }

    @PostMapping("/api/drives/{driveId}/outreach/draft")
    public OutreachService.Draft draft(@PathVariable UUID driveId, @Valid @RequestBody DraftRequest req) {
        return outreach.draft(driveId, req.roleId(), req.candidateId());
    }

    @PostMapping("/api/drives/{driveId}/outreach")
    @ResponseStatus(HttpStatus.CREATED)
    public OutreachView.Sent send(@PathVariable UUID driveId, @Valid @RequestBody SendRequest req) {
        return outreach.send(driveId, req.roleId(), req.candidateId(), req.subject(), req.body());
    }

    @GetMapping("/api/drives/{driveId}/outreach")
    public List<OutreachView.Status> forDrive(@PathVariable UUID driveId) {
        return outreach.forDrive(driveId);
    }

    @GetMapping("/api/outreach/inbox")
    public List<OutreachView.Message> inbox() {
        return outreach.inbox();
    }

    @PostMapping("/api/outreach/{id}/respond")
    public OutreachView.Message respond(@PathVariable UUID id, @Valid @RequestBody RespondRequest req) {
        return outreach.respond(id, req.interested());
    }
}
