package dev.notch.api.review;

import dev.notch.api.config.ApiException;
import dev.notch.api.ingest.ImportReport;
import dev.notch.api.ingest.ImportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * One place to add anything to a project: a codebase (a zip of code, or a folder the web app
 * zipped) is reviewed and mapped; a chat export, note or transcript is imported. The person
 * doesn't have to know which page takes what.
 */
@RestController
public class UploadController {

    /** {@code kind} is "code" (with {@code review}) or "chats" (with {@code imported}). */
    public record Uploaded(String kind, ReviewService.Summary review, ImportReport imported) {
    }

    private final ImportService imports;
    private final ReviewService reviews;

    public UploadController(ImportService imports, ReviewService reviews) {
        this.imports = imports;
        this.reviews = reviews;
    }

    @PostMapping("/api/projects/{projectId}/uploads")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Uploaded upload(@PathVariable UUID projectId, @RequestParam("file") MultipartFile file) {
        try {
            return new Uploaded("chats", null, imports.importFile(projectId, file, null));
        } catch (ApiException e) {
            // Not a chat export but a codebase: review it (which also maps it for Explain).
            if (!"code_zip".equals(e.code())) throw e;
            return new Uploaded("code", reviews.startZip(projectId, file), null);
        }
    }
}
