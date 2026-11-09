package dev.notch.api.security;

/** The two GitHub calls sign-in needs. An interface so tests can replace GitHub. */
public interface GitHubClient {

    record GitHubUser(long id, String login, String avatarUrl) {
    }

    /** Exchanges the OAuth code for an access token and reads the signed-in GitHub user. */
    GitHubUser signIn(String code);
}
