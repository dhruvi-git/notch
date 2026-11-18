package dev.notch.api.portfolio;

import dev.notch.api.config.ApiException;
import dev.notch.api.config.NotchProperties;
import dev.notch.api.ingest.ImportFileReader;
import dev.notch.api.ingest.core.ParsedConversation;
import dev.notch.api.portfolio.core.ChatSkillSuggester;
import dev.notch.api.portfolio.core.RepoEvidence;
import dev.notch.api.security.CurrentUser;
import dev.notch.api.security.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Brings real evidence into a portfolio: public GitHub repositories, and skill suggestions from chat exports. */
@Service
public class EvidenceImportService {

    public record GitHubSyncResult(String username, int listed, int read, int added, int updated, int removed,
                                   int forksSkipped, int withoutOwnCommits, List<String> duplicates, boolean partial,
                                   String note) {
    }

    public record ChatSuggestion(UUID evidenceId, int skillId, String skill, int conversations, int messages, double depth) {
    }

    public record ChatImportResult(String format, int conversations, List<ChatSuggestion> suggestions) {
    }

    static final Pattern GITHUB_LOGIN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");
    static final int MAX_REPOS_WITH_TOKEN = 30;
    static final int MAX_REPOS_WITHOUT_TOKEN = 12;

    private final GitHubRepoClient github;
    private final EvidenceWriter writer;
    private final CatalogService catalog;
    private final CandidatePool pool;
    private final ImportFileReader files;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final NotchProperties props;
    private final Set<UUID> syncing = ConcurrentHashMap.newKeySet();

    public EvidenceImportService(GitHubRepoClient github, EvidenceWriter writer, CatalogService catalog, CandidatePool pool,
                                 ImportFileReader files, UserRepository users, CurrentUser currentUser, NotchProperties props) {
        this.github = github;
        this.writer = writer;
        this.catalog = catalog;
        this.pool = pool;
        this.files = files;
        this.users = users;
        this.currentUser = currentUser;
        this.props = props;
    }

    /**
     * Reads the user's public repositories and records each as evidence. The username defaults to
     * the GitHub account the user signed in with.
     */
    public GitHubSyncResult syncGitHub(String requested) {
        UUID me = currentUser.id();
        String username = requested == null || requested.isBlank() ? signedInGitHubLogin(me) : requested.strip();
        if (!GITHUB_LOGIN.matcher(username).matches()) {
            throw ApiException.badRequest("invalid_github_username", "That isn't a valid GitHub username.");
        }
        if (!syncing.add(me)) throw ApiException.conflict("sync_running", "A GitHub sync is already running. Try again in a minute.");
        try {
            int limit = github.authenticated() ? MAX_REPOS_WITH_TOKEN : MAX_REPOS_WITHOUT_TOKEN;
            GitHubRepoClient.Listing listing;
            try {
                listing = github.fetch(username, limit);
            } catch (GitHubRepoClient.UserNotFound e) {
                throw ApiException.notFoundWith("github_user_not_found", "GitHub has no user called " + username + ".");
            } catch (GitHubRepoClient.RateLimited e) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "github_rate_limited",
                        "GitHub's hourly limit for this server was reached. Try again in an hour.");
            } catch (IllegalStateException e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "github_unavailable", "GitHub couldn't be reached. Try again shortly.");
            }

            List<RepoEvidence.Draft> drafts = new ArrayList<>();
            Set<String> skippedUrls = new HashSet<>();
            int noOwn = 0;
            for (RepoEvidence.RepoData r : listing.repos()) {
                if (RepoEvidence.skip(r).isPresent()) {
                    noOwn++;
                    skippedUrls.add(r.htmlUrl());
                    continue;
                }
                drafts.add(RepoEvidence.build(r, catalog.catalog()));
            }
            // Listed repositories not read this time keep any earlier evidence; ones read and skipped lose it.
            Set<String> keep = new HashSet<>(listing.listedUrls());
            keep.removeAll(skippedUrls);
            EvidenceWriter.RepoWrite w = writer.writeRepos(me, drafts, keep);
            pool.invalidate();

            String note = listing.partial() ? "GitHub's hourly limit stopped the sync early; run it again later for the rest."
                    : listing.repos().size() < listing.listedUrls().size() - listing.forks()
                    ? "Read the " + listing.repos().size() + " most recently pushed repositories." : null;
            return new GitHubSyncResult(username, listing.listedUrls().size(), listing.repos().size(), w.added(), w.updated(),
                    w.removed(), listing.forks(), noOwn, w.duplicates(), listing.partial(), note);
        } finally {
            syncing.remove(me);
        }
    }

    /**
     * Reads a ChatGPT or Claude export, suggests the skills the candidate kept asking about, and
     * replaces earlier suggestions. Nothing counts until approved. The file itself is not kept.
     */
    public ChatImportResult importChats(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("empty_file", "Choose your export file to upload.");
        if (file.getSize() > props.ingest().maxFileBytes()) {
            throw ApiException.tooLarge("Files can be up to " + (props.ingest().maxFileBytes() >> 20) + " MB.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("unreadable_file", "The upload could not be read. Try again.");
        }
        ImportFileReader.Result read = files.read(file.getOriginalFilename(), bytes, null);
        List<ParsedConversation> conversations = read.conversations();
        List<ChatSkillSuggester.Suggestion> suggestions = ChatSkillSuggester.suggest(conversations, catalog.catalog());

        String source = switch (read.format().name()) {
            case "CHATGPT" -> "ChatGPT";
            case "CLAUDE" -> "Claude";
            default -> "AI chat";
        };
        List<UUID> ids = writer.replaceChatSuggestions(currentUser.id(), source, suggestions);
        pool.invalidate();
        List<ChatSuggestion> out = new ArrayList<>();
        for (int i = 0; i < suggestions.size(); i++) {
            ChatSkillSuggester.Suggestion s = suggestions.get(i);
            out.add(new ChatSuggestion(ids.get(i), s.skill().id(), s.skill().name(), s.conversations(), s.messages(), s.depth()));
        }
        return new ChatImportResult(read.format().name().toLowerCase(Locale.ROOT), conversations.size(), out);
    }

    private String signedInGitHubLogin(UUID userId) {
        UserRepository.User u = users.find(userId).orElseThrow(ApiException::signInRequired);
        if (!u.github()) {
            throw ApiException.badRequest("github_username_required", "Enter your GitHub username to sync your repositories.");
        }
        return u.handle();
    }
}
