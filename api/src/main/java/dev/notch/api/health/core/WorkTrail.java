package dev.notch.api.health.core;

import dev.notch.explain.CodeMap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the trail agents leave in captured work: the files a Claude Code session edited
 * ("Edited C:\...\src\db.ts") and the commits git capture recorded ("Commit 3fba77c: message").
 * Paths are matched to the code map, so an absolute path from one machine becomes the file the
 * map knows ("src/db.ts").
 */
public final class WorkTrail {

    private static final Pattern EDITED = Pattern.compile("(?m)^\\s*Edited (\\S.*?)\\s*$");
    private static final Pattern COMMIT = Pattern.compile("Commit ([0-9a-f]{7,40}): (.+?)(?:\\s+Changed:|$)", Pattern.DOTALL);

    public record Commit(String sha, String subject) {
    }

    private WorkTrail() {
    }

    /** The paths a session's text says were edited, as written (absolute or relative). */
    public static List<String> editedPaths(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = EDITED.matcher(text);
        while (m.find()) {
            String p = m.group(1).replace('\\', '/');
            if (!p.equals("a file")) out.add(p);
        }
        return new ArrayList<>(out);
    }

    /** The commit a captured passage records, or null. */
    public static Commit commit(String text) {
        Matcher m = COMMIT.matcher(text);
        if (!m.find()) return null;
        return new Commit(m.group(1).substring(0, 7), m.group(2).replaceAll("\\s+", " ").strip());
    }

    /**
     * The map's file for an edited path: the longest map path the edited path ends with (so
     * "C:/x/worli/components/A.tsx" is "components/A.tsx"). Null when the map doesn't have it,
     * such as a file outside the repository or one deleted since.
     */
    public static String toMapPath(String edited, CodeMap map) {
        if (map == null) return null;
        String e = edited.replace('\\', '/').toLowerCase(Locale.ROOT);
        String best = null;
        for (CodeMap.FileInfo f : map.files()) {
            String p = f.path().toLowerCase(Locale.ROOT);
            if ((e.equals(p) || e.endsWith("/" + p)) && (best == null || p.length() > best.length())) best = f.path();
        }
        return best;
    }
}
