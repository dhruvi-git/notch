package dev.notch.api.security;

/** The calls Google sign-in needs. An interface so tests can replace Google. */
public interface GoogleClient {

    /** {@code sub} is Google's stable id for the account. */
    record GoogleUser(String sub, String email, String name, String pictureUrl) {
    }

    /** Exchanges the OAuth code for an access token and reads the signed-in Google account. */
    GoogleUser signIn(String code);
}
