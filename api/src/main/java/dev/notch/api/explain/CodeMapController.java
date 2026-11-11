package dev.notch.api.explain;

import dev.notch.explain.CodeMap;
import org.springframework.http.HttpStatus;
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

/** Explain: code maps, and what, how and why for each file. */
@RestController
public class CodeMapController {

    public record UploadRequest(String label, CodeMap map) {
    }

    private final CodeMapService service;

    public CodeMapController(CodeMapService service) {
        this.service = service;
    }

    /** A map built by {@code notch map}: paths, imports, names and summaries, no code. */
    @PostMapping("/api/projects/{projectId}/code-maps")
    @ResponseStatus(HttpStatus.CREATED)
    public CodeMapService.Uploaded upload(@PathVariable UUID projectId, @RequestBody UploadRequest req) {
        return service.upload(projectId, req.label(), req.map());
    }

    /** A zipped codebase; it's mapped on the server and the code is not kept. */
    @PostMapping("/api/projects/{projectId}/code-maps/zip")
    @ResponseStatus(HttpStatus.CREATED)
    public CodeMapService.Uploaded uploadZip(@PathVariable UUID projectId, @RequestParam("file") MultipartFile file) {
        return service.uploadZip(projectId, file);
    }

    @GetMapping("/api/projects/{projectId}/code-map")
    public CodeMapService.Overview overview(@PathVariable UUID projectId) {
        return service.overview(projectId);
    }

    @GetMapping("/api/projects/{projectId}/code-map/files")
    public List<CodeMapService.FileSummary> files(@PathVariable UUID projectId) {
        return service.files(projectId);
    }

    @GetMapping("/api/projects/{projectId}/code-map/file")
    public CodeMapService.FileView file(@PathVariable UUID projectId, @RequestParam("path") String path) {
        return service.file(projectId, path);
    }
}
