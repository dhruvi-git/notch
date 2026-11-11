package dev.notch.api.config;

import org.springframework.http.HttpStatus;

/**
 * An error the API reports to the client. {@code code} is a stable machine-readable string;
 * the message is written for people and shown in the UI.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " was not found.");
    }

    /** Not found, with a specific code the client can act on (such as "no_code_map"). */
    public static ApiException notFoundWith(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException tooLarge(String message) {
        return new ApiException(HttpStatus.valueOf(413), "file_too_large", message);
    }

    public static ApiException limitReached(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "daily_limit_reached", message);
    }

    public static ApiException signInRequired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "sign_in_required", "Sign in to continue.");
    }
}
