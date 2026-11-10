package dev.notch.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the added lines out of a unified diff, as produced by {@code git diff}. */
public final class DiffParser {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private DiffParser() {
    }

    public static List<FileChange> parse(String diff) {
        List<FileChange> out = new ArrayList<>();
        String path = null;
        List<FileChange.Line> added = new ArrayList<>();
        int newLine = 0;
        boolean inHunk = false;
        for (String line : diff.split("\r?\n", -1)) {
            if (line.startsWith("diff --git ")) {
                flush(out, path, added);
                path = null;
                added = new ArrayList<>();
                inHunk = false;
            } else if (line.startsWith("+++ ")) {
                String target = line.substring(4).trim();
                int tab = target.indexOf('\t');
                if (tab >= 0) target = target.substring(0, tab);
                path = target.equals("/dev/null") ? null : stripPrefix(target);
                inHunk = false;
            } else if (line.startsWith("--- ") && !inHunk) {
                // old file name: not needed
            } else if (line.startsWith("@@")) {
                Matcher m = HUNK.matcher(line);
                if (m.find()) {
                    newLine = Integer.parseInt(m.group(1));
                    inHunk = true;
                }
            } else if (inHunk && path != null) {
                if (line.startsWith("+")) {
                    added.add(new FileChange.Line(newLine, line.substring(1)));
                    newLine++;
                } else if (line.startsWith(" ")) {
                    newLine++;
                }
                // "-" lines and "\ No newline at end of file" don't move the new-file counter
            }
        }
        flush(out, path, added);
        return out;
    }

    private static void flush(List<FileChange> out, String path, List<FileChange.Line> added) {
        if (path != null && !added.isEmpty()) out.add(new FileChange(path, added));
    }

    private static String stripPrefix(String p) {
        if (p.startsWith("\"") && p.endsWith("\"") && p.length() > 1) p = p.substring(1, p.length() - 1);
        return p.startsWith("b/") ? p.substring(2) : p;
    }

    /** A whole new file's content as a change, for editor hooks that see content, not diffs. */
    public static FileChange fromContent(String path, String content, boolean numbered) {
        List<FileChange.Line> lines = new ArrayList<>();
        String[] parts = content.split("\r?\n", -1);
        for (int i = 0; i < parts.length; i++) lines.add(new FileChange.Line(numbered ? i + 1 : 0, parts[i]));
        return new FileChange(path, lines);
    }
}
