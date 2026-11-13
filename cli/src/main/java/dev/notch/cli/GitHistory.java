package dev.notch.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Commits as Notch captures them: message, date and the files changed, newest last. */
final class GitHistory {

    record Commit(String hash, Instant at, String subject, String body, List<String> files) {
        /** What Notch stores for the commit: enough to find decisions and fixes, without the diff. */
        String text() {
            StringBuilder sb = new StringBuilder("Commit ").append(hash, 0, Math.min(7, hash.length())).append(": ").append(subject);
            String cleanBody = TRAILERS.matcher(body).replaceAll("").strip();
            if (!cleanBody.isBlank()) sb.append("\n\n").append(cleanBody);
            if (!files.isEmpty()) {
                sb.append("\n\nChanged: ").append(String.join(", ", files.subList(0, Math.min(15, files.size()))));
                if (files.size() > 15) sb.append(" and ").append(files.size() - 15).append(" more");
            }
            return sb.toString();
        }
    }

    static final int FIRST_SYNC = 100;
    /** Tool-added trailers (session links, co-authors, sign-offs): bookkeeping, not what the commit was about. */
    static final java.util.regex.Pattern TRAILERS = java.util.regex.Pattern.compile(
            "(?im)^(co-authored-by|signed-off-by|claude-session|generated-by|change-id|reviewed-by):.*$");
    private static final char RECORD = '\u001e';
    private static final char FIELD = '\u001f';
    private static final char END = '\u001d';

    private GitHistory() {
    }

    /** Commits after {@code since} (or the last {@value #FIRST_SYNC} when null), oldest first. */
    static List<Commit> read(Path root, String since) throws IOException {
        List<String> args = new ArrayList<>(List.of("log", "--name-only", "--no-color",
                "--format=" + RECORD + "%H" + FIELD + "%aI" + FIELD + "%s" + FIELD + "%b" + END));
        if (since != null) args.add(since + "..HEAD");
        else args.add("--max-count=" + FIRST_SYNC);
        String out;
        try {
            out = Git.run(root, args.toArray(String[]::new));
        } catch (IOException e) {
            if (since != null) return read(root, null);  // history was rewritten: start again
            if (e.getMessage() != null && e.getMessage().contains("does not have any commits")) return List.of();
            throw e;
        }
        return parse(out);
    }

    static List<Commit> parse(String out) {
        List<Commit> commits = new ArrayList<>();
        for (String record : out.split(String.valueOf(RECORD))) {
            int end = record.indexOf(END);
            if (end < 0) continue;
            String[] f = record.substring(0, end).split(String.valueOf(FIELD), -1);
            if (f.length < 4) continue;
            List<String> files = new ArrayList<>();
            for (String line : record.substring(end + 1).split("\\R")) if (!line.isBlank()) files.add(line.strip());
            commits.add(new Commit(f[0], instant(f[1]), f[2], f[3], files));
        }
        java.util.Collections.reverse(commits);  // git lists newest first
        return commits;
    }

    static String head(Path root) {
        try {
            return Git.run(root, "rev-parse", "HEAD").strip();
        } catch (IOException e) {
            return null;
        }
    }

    static String branch(Path root) {
        try {
            return Git.run(root, "rev-parse", "--abbrev-ref", "HEAD").strip();
        } catch (IOException e) {
            return null;
        }
    }

    /** Uncommitted and untracked files, as git status reports them. */
    static List<String> uncommitted(Path root) {
        List<String> out = new ArrayList<>();
        try {
            for (String line : Git.run(root, "status", "--porcelain").split("\\R")) {
                if (line.length() > 3) out.add(line.substring(3).strip());
            }
        } catch (IOException ignored) {
            // not a repository, or git unavailable
        }
        return out;
    }

    static String lastCommitLine(Path root) {
        try {
            return Git.run(root, "log", "-1", "--format=%h %s (%ar)").strip();
        } catch (IOException e) {
            return null;
        }
    }

    private static Instant instant(String s) {
        try {
            return Instant.parse(s.strip().replaceAll("([+-]\\d{2}):(\\d{2})$", "$1:$2"));
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(s.strip()).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
