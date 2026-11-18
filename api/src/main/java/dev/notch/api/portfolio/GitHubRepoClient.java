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

    /** Calls allowed per hour decide how many repositories one sync may read. */
    boolean authenticated();

    final class UserNotFound extends RuntimeException {
        public UserNotFound(String username) {
            super("GitHub has no user called " + username);
        }
    }

    final class RateLimited extends RuntimeException {
        public RateLimited() {
            super("GitHub's rate limit was reached");
        }
    }
}
