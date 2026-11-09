package dev.notch.api.security;

import dev.notch.api.config.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.UUID;

/** Reads the user that {@link AuthFilter} attached to the current request. */
@Component
public class RequestCurrentUser implements CurrentUser {

    @Override
    public UUID id() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object id = attrs == null ? null : attrs.getAttribute(AuthFilter.USER_ATTR, RequestAttributes.SCOPE_REQUEST);
        if (id instanceof UUID uuid) return uuid;
        throw ApiException.signInRequired();
    }
}
