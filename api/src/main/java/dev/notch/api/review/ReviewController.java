package dev.notch.api.review;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Review: an overall assessment of a codebase, its issues and recommendations. */
@RestController
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    /** A zipped codebase, reviewed in the background; poll the review until it's DONE or FAILED. */
    @PostMapping("/api/projects/{projectId}/reviews/zip")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReviewService.Summary reviewZip(@PathVariable UUID projectId, @RequestParam("file") MultipartFile file) {
        return service.startZip(projectId, file);
    }

    /** A review written by Claude Code (/notch review). */
    @PostMapping("/api/projects/{projectId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewService.Summary submit(@PathVariable UUID projectId, @RequestBody ReviewService.Submission submission) {
        return service.submit(projectId, submission);
    }

    @GetMapping("/api/projects/{projectId}/reviews")
    public List<ReviewService.Summary> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    @GetMapping("/api/projects/{projectId}/reviews/{reviewId}")
    public ReviewService.Detail get(@PathVariable UUID projectId, @PathVariable UUID reviewId) {
        return service.get(projectId, reviewId);
    }

    @DeleteMapping("/api/projects/{projectId}/reviews/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, @PathVariable UUID reviewId) {
        service.delete(projectId, reviewId);
    }
}
