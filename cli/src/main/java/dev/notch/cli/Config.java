package dev.notch.cli;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Where the CLI finds its settings, highest priority first:
 * command-line flags, environment variables ({@code NOTCH_API_URL}, {@code NOTCH_TOKEN},
 * {@code NOTCH_PROJECT}), the repository's {@code .notch} file (project id only, safe to
 * commit), then the user file written by {@code notch login}.
 */
public final class Config {

    public static final String DEFAULT_API = "http://localhost:8090";
    static final String REPO_FILE = ".notch";

    public final String api;
    public final String token;
    public final String project;
    final Path userFile;
    final Path cacheDir;

    Config(String api, String token, String project, Path userFile, Path cacheDir) {
        this.api = api;
        this.token = token;
        this.project = project;
        this.userFile = userFile;
        this.cacheDir = cacheDir;
    }

    public static Config load(Map<String, String> env, Path home, Path repoRoot) {
        Path configHome = env.containsKey("XDG_CONFIG_HOME") ? Path.of(env.get("XDG_CONFIG_HOME")) : home.resolve(".config");
        Path cacheHome = env.containsKey("XDG_CACHE_HOME") ? Path.of(env.get("XDG_CACHE_HOME")) : home.resolve(".cache");
        Path userFile = configHome.resolve("notch").resolve("config.properties");
        Properties user = read(userFile);
        Properties repo = repoRoot == null ? new Properties() : read(repoRoot.resolve(REPO_FILE));
        String api = first(env.get("NOTCH_API_URL"), user.getProperty("api"), DEFAULT_API);
        String token = first(env.get("NOTCH_TOKEN"), user.getProperty("token"));
        String project = first(env.get("NOTCH_PROJECT"), repo.getProperty("project"));
        return new Config(api.replaceAll("/+$", ""), token, project, userFile, cacheHome.resolve("notch"));
    }

    public Config withProject(String p) {
        return new Config(api, token, p, userFile, cacheDir);
    }

    void saveUser(String newApi, String newToken) throws IOException {
        Properties p = read(userFile);
        if (newApi != null) p.setProperty("api", newApi);
        if (newToken != null) p.setProperty("token", newToken);
        Files.createDirectories(userFile.getParent());
        try (Writer w = Files.newBufferedWriter(userFile, StandardCharsets.UTF_8)) {
            p.store(w, "Notch CLI. Contains your personal token: keep this file private.");
        }
        try {
            userFile.toFile().setReadable(false, false);
            userFile.toFile().setReadable(true, true);
            userFile.toFile().setWritable(false, false);
            userFile.toFile().setWritable(true, true);
        } catch (SecurityException ignored) {
            // best effort on systems without POSIX permissions
        }
    }

    static void saveRepo(Path repoRoot, String project) throws IOException {
        Files.writeString(repoRoot.resolve(REPO_FILE),
                "# Links this repository to a Notch project. Safe to commit; contains no secrets.\nproject=" + project + "\n",
                StandardCharsets.UTF_8);
    }

    private static Properties read(Path file) {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) {
                // an unreadable file behaves like a missing one
            }
        }
        return p;
    }

    private static String first(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.strip();
        return null;
    }
}
