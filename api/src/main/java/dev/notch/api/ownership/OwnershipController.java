package dev.notch.api.ownership;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Ownership Checks: start one on a repository, answer, finish; finished checks on public profiles are public. */
@RestController
public class OwnershipController {

    public record StartRequest(@NotNull UUID evidenceId) {
    }

    public record AnswerRequest(@Size(max = 5000) String answer, long millis, int pastes, int focusLost) {
    }

    private final OwnershipService ownership;

    public OwnershipController(OwnershipService ownership) {
        this.ownership = ownership;
    }

    @PostMapping("/api/ownership/checks")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckView start(@Valid @RequestBody StartRequest req) {
        return ownership.start(req.evidenceId());
    }

    @GetMapping("/api/ownership/checks/{checkId}")
    public CheckView get(@PathVariable UUID checkId) {
        return ownership.get(checkId);
    }

    @PutMapping("/api/ownership/checks/{checkId}/answers/{position}")
    public CheckView answer(@PathVariable UUID checkId, @PathVariable int position, @Valid @RequestBody AnswerRequest req) {
        return ownership.answer(checkId, position, new OwnershipService.AnswerInput(req.answer(), req.millis(), req.pastes(), req.focusLost()));
    }

    @PostMapping("/api/ownership/checks/{checkId}/finish")
    public CheckView finish(@PathVariable UUID checkId) {
        return ownership.finish(checkId);
    }

    @GetMapping("/public/ownership/{checkId}")
    public CheckView publicTranscript(@PathVariable UUID checkId) {
        return ownership.publicTranscript(checkId);
    }
}
