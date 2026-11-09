package dev.notch.api.security.core;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Only redirects to the app's own origins are allowed after sign-in, so the sign-in endpoint
 * cannot be used to send people to an attacker's site (an open redirect).
 */
public final class RedirectPolicy {

    private final Set<String> allowedOrigins;

    public RedirectPolicy(List<String> allowed) {
        this.allowedOrigins = allowed.stream().map(RedirectPolicy::origin).filter(o -> o != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allows(String url) {
        String o = origin(url);
        return o != null && allowedOrigins.contains(o);
    }

    /** scheme://host[:port], lower-cased; null for anything that is not a plain http(s) URL. */
    static String origin(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = new URI(url.strip());
            String scheme = u.getScheme();
            if (scheme == null || u.getHost() == null || u.getRawUserInfo() != null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (u.getPort() == -1 ? "" : ":" + u.getPort());
        } catch (Exception e) {
            return null;
        }
    }
}
