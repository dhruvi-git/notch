package dev.notch.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 problem response. Framework errors (validation, oversized
 * uploads, bad JSON) are handled by the base class; unexpected errors are logged and returned
 * without internal details. Message content is never logged.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        pd.setTitle(e.status().getReasonPhrase());
        pd.setProperty("code", e.code());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled error: {}", e.getClass().getName(), e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Please try again.");
        pd.setTitle("Internal Server Error");
        pd.setProperty("code", "internal_error");
        return pd;
    }
}
