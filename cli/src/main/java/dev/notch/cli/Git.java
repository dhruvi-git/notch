package dev.notch.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The few git commands the CLI needs, run as a subprocess. */
final class Git {

    private Git() {
    }

    /** The repository root, or null outside a repository. */
    static Path root(Path cwd) {
        try {
            return Path.of(run(cwd, "rev-parse", "--show-toplevel").strip());
        } catch (IOException e) {
            return null;
        }
    }

    /** Where hooks live, honouring core.hooksPath. */
    static Path hooksDir(Path root) throws IOException {
        Path p = Path.of(run(root, "rev-parse", "--git-path", "hooks").strip());
        return p.isAbsolute() ? p : root.resolve(p);
    }

    /** Tracked files plus new ones that aren't ignored: what .gitignore says belongs to the project. */
    static List<String> projectFiles(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        for (String p : run(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split("\0")) {
            if (!p.isBlank()) out.add(p);
        }
        return out;
    }

    static String stagedDiff(Path root) throws IOException {
        return run(root, "diff", "--cached", "--unified=0", "--no-color", "--no-ext-diff");
    }

    /** Changes on this branch since it left {@code base}, as a pull request would show them. */
    static String diffSince(Path root, String base) throws IOException {
        return run(root, "diff", "--unified=0", "--no-color", "--no-ext-diff", base + "...HEAD");
    }

    static String run(Path cwd, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git " + args[0] + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (p.exitValue() != 0) throw new IOException("git " + String.join(" ", args) + " failed: " + err.strip());
        return out;
    }
}
