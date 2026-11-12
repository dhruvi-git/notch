package dev.notch.api.llm;

import java.util.Locale;

/** Decides whether to use a model provider from its setting and API key. */
public final class ProviderChoice {

    private ProviderChoice() {
    }

    /**
     * True when {@code setting} names {@code provider} (a key is then required, so a missing key
     * fails at startup rather than at the first question), or is "auto" (or empty) and a key is set.
     */
    public static boolean use(String setting, String provider, String key, String keyName) {
        String s = setting == null || setting.isBlank() ? "auto" : setting.strip().toLowerCase(Locale.ROOT);
        boolean hasKey = key != null && !key.isBlank();
        if (s.equals(provider)) {
            if (!hasKey) throw new IllegalStateException(provider + " was chosen but " + keyName + " is not set.");
            return true;
        }
        return s.equals("auto") && hasKey;
    }
}
