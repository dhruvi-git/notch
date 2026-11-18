package dev.notch.api.portfolio;

import dev.notch.api.portfolio.core.RepoEvidence.RepoData;

import java.util.List;

/** Reads a GitHub user's public repositories and their commits. */
public interface GitHubRepoClient {

    /**
     * @param repos       details of the most recently pushed non-fork, non-empty repositories, up to the limit
     * @param listedUrls  every repository in the listing, fetched or not (to tell deleted repos from unfetched ones)
     * @param forks       forks in the listing, skipped without further calls
     * @param partial     true when GitHub's rate limit stopped the fetch early
     */
    record Listing(List<RepoData> repos, List<String> listedUrls, int forks, boolean partial) {
    }

    Listing fetch(String username, int maxRepos);

    /**
     * The source files of one repository worth asking about in an Ownership Check, chosen by
     * {@link dev.notch.api.ownership.core.SourceSelector}: one call for the repository, one for its
     * file tree, and one per file read.
     *
     * @param fullName "owner/name"
     */
    List<dev.notch.api.ownership.core.RegionExtractor.SourceFile> sourceFiles(String fullName, int maxFiles);

    /** Calls allowed per hour decide how many repositories one sync may read. */
    boolean authenticated();

    final class UserNotFound extends RuntimeException {
        public UserNotFound(String username) {
            super("GitHub has no user called " + username);
        }
    }

    final class RepoNotFound extends RuntimeException {
        public RepoNotFound(String fullName) {
            super("GitHub has no public repository " + fullName);
        }
    }

    final class RateLimited extends RuntimeException {
        public RateLimited() {
            super("GitHub's rate limit was reached");
        }
    }
}
