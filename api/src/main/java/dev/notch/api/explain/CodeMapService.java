package dev.notch.api.explain;

import dev.notch.api.config.ApiException;
import dev.notch.api.crypto.ContentCipher;
import dev.notch.api.explain.core.CodeDecisionLinks;
import dev.notch.api.explain.core.MapLimits;
import dev.notch.api.memory.MemoryCodec;
import dev.notch.api.memory.MemoryPayload;
import dev.notch.api.memory.MemoryRepository;
import dev.notch.api.project.Project;
import dev.notch.api.project.ProjectService;
import dev.notch.api.search.IndexRegistry;
import dev.notch.api.search.core.IndexedChunk;
import dev.notch.api.search.core.ProjectIndex;
import dev.notch.api.security.CurrentUser;
import dev.notch.api.usage.UsageService;
import dev.notch.explain.CodeMap;
import dev.notch.explain.CodeMapBuilder;
import dev.notch.explain.Role;
import dev.notch.explain.ZipSource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Explain: stores a project's code map and answers what a file does, how it fits, and why it
 * is the way it is, joining the map with the project's decisions and conversations.
 */
@Service
public class CodeMapService {

    static final long MAX_ZIP_BYTES = 25L * 1024 * 1024;

    public record Uploaded(UUID id, String label, String source, Instant createdAt, CodeMap.Stats stats) {
    }

    public record FileSummary(String path, Role role, String summary, int lines, String module, String route,
                              int importedBy, boolean conflict) {
    }

    public record EntryPoint(String path, Role role, String route, List<String> endpoints, String summary) {
    }

    public record Overview(UUID id, String label, String source, Instant createdAt, CodeMap.Stats stats,
                           List<CodeMap.Module> modules, List<CodeMap.Area> areas, List<FileSummary> startHere,
                           List<EntryPoint> entryPoints, List<FileSummary> possiblyUnused,
                           List<CodeDecisionLinks.Conflict> conflicts, List<CodeDecisionLinks.ToolUse> tools) {
    }

    /**
     * A conversation passage that mentions the file or one of its folders, and the reply that
     * followed it. The reply matters: an answer rarely repeats the file name it's answering about.
     */
    public record Mention(String matched, UUID conversationId, String conversationTitle, Instant sentAt, String role,
                          String text, boolean decision, String replyRole, String replyText) {
    }

    public record FileView(CodeMap.FileInfo file, List<String> affected, List<CodeDecisionLinks.Link> decisions,
                           List<Mention> mentions, String mapLabel, Instant mapCreatedAt) {
    }

    private final CodeMapRepository repo;
    private final ProjectService projects;
    private final CurrentUser currentUser;
    private final ContentCipher cipher;
    private final ObjectMapper mapper;
    private final UsageService usage;
    private final MemoryRepository memories;
    private final MemoryCodec memoryCodec;
    private final IndexRegistry indexes;
    private final dev.notch.api.ingest.MessageRepository messages;

    public CodeMapService(CodeMapRepository repo, ProjectService projects, CurrentUser currentUser, ContentCipher cipher,
                          ObjectMapper mapper, UsageService usage, MemoryRepository memories, MemoryCodec memoryCodec,
                          IndexRegistry indexes, dev.notch.api.ingest.MessageRepository messages) {
        this.messages = messages;
        this.repo = repo;
        this.projects = projects;
        this.currentUser = currentUser;
        this.cipher = cipher;
        this.mapper = mapper;
        this.usage = usage;
        this.memories = memories;
        this.memoryCodec = memoryCodec;
        this.indexes = indexes;
    }

    /** A map built by the CLI on the developer's machine. */
    public Uploaded upload(UUID projectId, String label, CodeMap map) {
        return store(projects.require(projectId), "cli", label, map);
    }

    /** A zipped codebase: mapped here, then the code is discarded; only the map is kept. */
    public Uploaded uploadZip(UUID projectId, MultipartFile file) {
        Project project = projects.require(projectId);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("empty_file", "Choose a .zip of your code.");
        if (file.getSize() > MAX_ZIP_BYTES) {
            throw ApiException.tooLarge("The zip is over 25 MB. Leave out node_modules and build folders, or use notch map.");
        }
        ZipSource.Result read;
        try {
            read = ZipSource.read(file.getBytes());
        } catch (ZipSource.TooLargeException e) {
            throw ApiException.tooLarge(e.getMessage());
        } catch (IOException e) {
            throw ApiException.badRequest("invalid_zip", "That doesn't look like a valid zip file.");
        }
        CodeMap map = new CodeMapBuilder().build(read.files());
        if (map.files().isEmpty()) throw ApiException.badRequest("no_source", "The zip has no source files Notch can read.");
        String name = file.getOriginalFilename() == null ? "code.zip" : file.getOriginalFilename();
        return store(project, "zip", name.replaceAll("\\.zip$", ""), map);
    }

    private Uploaded store(Project project, String source, String label, CodeMap map) {
        String problem = MapLimits.problem(map);
        if (problem != null) throw ApiException.badRequest("invalid_map", problem);
        UUID userId = currentUser.id();
        usage.consume(userId, UsageService.IMPORT);
        String cleanLabel = label == null || label.isBlank() ? "codebase" : label.strip();
        if (cleanLabel.length() > 120) cleanLabel = cleanLabel.substring(0, 120);
        UUID id = UUID.randomUUID();
        byte[] sealed = cipher.encrypt(new String(mapper.writeValueAsBytes(map), StandardCharsets.UTF_8), userId.toString());
        repo.insert(id, project.id(), userId, source, cleanLabel, map.files().size(), sealed);
        return new Uploaded(id, cleanLabel, source, Instant.now(), map.stats());
    }

    public Overview overview(UUID projectId) {
        Project project = projects.require(projectId);
        CodeMapRepository.Stored s = latest(project);
        CodeMap map = open(s);
        List<CodeDecisionLinks.Decision> decisions = decisions(project);
        List<CodeDecisionLinks.Conflict> conflicts = CodeDecisionLinks.conflicts(map, decisions);
        java.util.Set<String> conflicted = new java.util.HashSet<>();
        conflicts.forEach(c -> conflicted.add(c.path()));
        List<FileSummary> start = map.startHere().stream().map(map::file).map(f -> summary(f, conflicted)).toList();
        List<EntryPoint> entries = map.entryPoints().stream().map(map::file)
                .map(f -> new EntryPoint(f.path(), f.role(), f.route(), f.endpoints(), f.summary())).toList();
        List<FileSummary> unused = map.possiblyUnused().stream().map(map::file).map(f -> summary(f, conflicted)).toList();
        return new Overview(s.id(), s.label(), s.source(), s.createdAt(), map.stats(), map.modules(), map.areas(), start,
                entries, unused, conflicts, CodeDecisionLinks.toolUsage(map, decisions));
    }

    public List<FileSummary> files(UUID projectId) {
        Project project = projects.require(projectId);
        CodeMap map = open(latest(project));
        java.util.Set<String> conflicted = new java.util.HashSet<>();
        CodeDecisionLinks.conflicts(map, decisions(project)).forEach(c -> conflicted.add(c.path()));
        return map.files().stream().map(f -> summary(f, conflicted)).toList();
    }

    public FileView file(UUID projectId, String path) {
        Project project = projects.require(projectId);
        CodeMapRepository.Stored s = latest(project);
        CodeMap map = open(s);
        CodeMap.FileInfo f = map.file(path);
        if (f == null) throw ApiException.notFound("File");
        return new FileView(f, map.affectedBy(path), CodeDecisionLinks.forFile(f, decisions(project)),
                mentions(project, path), s.label(), s.createdAt());
    }

    /**
     * Conversations that mention the file by path or by a distinctive name, or mention one of
     * its folders. Folder names are checked against the radix tree of keys first, so only
     * folders that were ever mentioned are searched for.
     */
    private List<Mention> mentions(Project project, String path) {
        ProjectIndex index = indexes.get(project);
        if (index.isEmpty()) return List.of();
        List<String> terms = new ArrayList<>();
        terms.add(path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.length() >= 6 && name.contains(".") && !name.matches("(?i)(index|page|layout|route|main|app|utils?)\\..*")) terms.add(name);
        for (String d = parent(path); d.contains("/"); d = parent(d)) {
            if (index.hasKey(d) || index.hasKey(d + "/")) terms.add(d + "/");
        }
        Map<UUID, Mention> found = new LinkedHashMap<>();
        java.util.Set<UUID> messagesSeen = new java.util.HashSet<>();
        UUID userId = currentUser.id();
        for (String term : terms) {
            for (IndexedChunk c : index.chunksContaining(term)) {
                if (!messagesSeen.add(c.messageId())) continue;  // one mention per message
                String replyRole = null;
                String replyText = null;
                var reply = messages.nextReply(c.messageId(), userId);
                if (reply.isPresent()) {
                    replyRole = reply.get().role();
                    replyText = clip(cipher.decrypt(reply.get().body(), userId.toString()), 420);
                }
                found.put(c.chunkId(), new Mention(term, c.conversationId(), c.conversationTitle(), c.sentAt(), c.role(),
                        excerpt(c.text(), term), c.decision(), replyRole, replyText));
                if (found.size() >= 8) return new ArrayList<>(found.values());
            }
        }
        return new ArrayList<>(found.values());
    }

    private static String clip(String s, int max) {
        String t = s.strip();
        if (t.length() <= max) return t;
        int cut = t.lastIndexOf(' ', max);
        return t.substring(0, cut > max / 2 ? cut : max).strip() + "\u2026";
    }

    private static String excerpt(String text, String term) {
        int at = text.toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT));
        int start = Math.max(0, at - 140);
        int end = Math.min(text.length(), (at < 0 ? 0 : at) + term.length() + 180);
        return (start > 0 ? "\u2026" : "") + text.substring(start, end).strip() + (end < text.length() ? "\u2026" : "");
    }

    private List<CodeDecisionLinks.Decision> decisions(Project project) {
        UUID userId = currentUser.id();
        List<CodeDecisionLinks.Decision> out = new ArrayList<>();
        for (MemoryRepository.StoredMemory m : memories.list(project.id(), userId, "DECISION", "ACTIVE", 500)) {
            MemoryPayload p = memoryCodec.open(m.payload(), userId);
            out.add(new CodeDecisionLinks.Decision(m.id().toString(), p.title(), p.rejectedOptions(), m.createdAt()));
        }
        return out;
    }

    private CodeMapRepository.Stored latest(Project project) {
        return repo.latest(project.id(), currentUser.id()).orElseThrow(() -> ApiException.notFoundWith("no_code_map",
                "No code map for this project yet. Upload a zip, or run notch map in the repository."));
    }

    private CodeMap open(CodeMapRepository.Stored s) {
        return mapper.readValue(cipher.decrypt(s.payload(), currentUser.id().toString()).getBytes(StandardCharsets.UTF_8), CodeMap.class);
    }

    private static FileSummary summary(CodeMap.FileInfo f, java.util.Set<String> conflicted) {
        return new FileSummary(f.path(), f.role(), f.summary(), f.lines(), f.module(), f.route(), f.importedBy().size(),
                conflicted.contains(f.path()));
    }

    private static String parent(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }
}
