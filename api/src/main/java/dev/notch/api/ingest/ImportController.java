package dev.notch.api.ingest;

import dev.notch.api.config.ApiException;
import dev.notch.api.project.ProjectService;
import dev.notch.api.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class ImportController {

    private final ImportService service;
    private final ImportRepository imports;
    private final ConversationRepository conversations;
    private final ProjectService projects;
    private final CurrentUser currentUser;

    public ImportController(ImportService service, ImportRepository imports,
                            ConversationRepository conversations, ProjectService projects,
                            CurrentUser currentUser) {
        this.service = service;
        this.imports = imports;
        this.conversations = conversations;
        this.projects = projects;
        this.currentUser = currentUser;
    }

    /**
     * Uploads a ChatGPT or Claude export (zip or conversations.json), a markdown note or a
     * transcript. Returns 202: messages are stored, and memories follow in the background.
     */
    @PostMapping("/imports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportReport upload(@PathVariable UUID projectId,
                               @RequestParam("file") MultipartFile file,
                               @RequestParam(value = "format", required = false) String format) {
        return service.importFile(projectId, file, format);
    }

    @GetMapping("/imports")
    public List<ImportReport> list(@PathVariable UUID projectId) {
        projects.require(projectId);
        return imports.list(projectId, currentUser.id());
    }

    @GetMapping("/imports/{importId}")
    public ImportReport get(@PathVariable UUID projectId, @PathVariable UUID importId) {
        projects.require(projectId);
        return imports.find(importId, projectId, currentUser.id())
                .orElseThrow(() -> ApiException.notFound("Import"));
    }

    @GetMapping("/conversations")
    public List<ConversationRepository.ConversationSummary> conversations(@PathVariable UUID projectId) {
        projects.require(projectId);
        return conversations.list(projectId, currentUser.id());
    }
}
