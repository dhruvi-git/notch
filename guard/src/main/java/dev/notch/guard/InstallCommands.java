package dev.notch.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads package installs out of a shell command ("npm install mongoose", "pip install pymongo"),
 * so a coding agent's terminal command can be checked like the manifest change it causes.
 *
 * <p>Each install becomes an added line in the manifest it would change, which the guard treats
 * as a strong signal. Flags and their values, local paths and URLs are skipped.
 */
public final class InstallCommands {

    private static final Pattern SEPARATORS = Pattern.compile("&&|\\|\\||;|\\||\\r?\\n");

    private InstallCommands() {
    }

    public static List<FileChange> parse(String command) {
        List<FileChange> out = new ArrayList<>();
        if (command == null || command.isBlank()) return out;
        for (String part : SEPARATORS.split(command)) {
            List<String> t = tokens(part);
            if (t.isEmpty()) continue;
            FileChange c = install(t);
            if (c != null && !c.added().isEmpty()) out.add(c);
        }
        return out;
    }

    private static FileChange install(List<String> t) {
        // Skip leading sudo, env assignments and "npx"/"python -m".
        int i = 0;
        while (i < t.size() && (t.get(i).equals("sudo") || t.get(i).contains("=") && !t.get(i).startsWith("-"))) i++;
        if (i < t.size() - 1 && (t.get(i).startsWith("python") && t.get(i + 1).equals("-m"))) i += 2;
        if (i >= t.size()) return null;
        String tool = t.get(i).toLowerCase(Locale.ROOT);
        String verb = i + 1 < t.size() ? t.get(i + 1).toLowerCase(Locale.ROOT) : "";
        List<String> args = t.subList(Math.min(i + 2, t.size()), t.size());
        return switch (tool) {
            case "npm", "pnpm", "bun" -> List.of("install", "i", "add").contains(verb)
                    ? manifest("package.json", packages(args, true), p -> "\"" + p + "\": \"*\",") : null;
            case "yarn" -> verb.equals("add") ? manifest("package.json", packages(args, true), p -> "\"" + p + "\": \"*\",") : null;
            case "pip", "pip3", "pipenv" -> verb.equals("install") ? manifest("requirements.txt", packages(args, false), p -> p) : null;
            case "poetry" -> verb.equals("add") ? manifest("pyproject.toml", packages(args, false), p -> p + " = \"*\"") : null;
            case "uv" -> verb.equals("add") ? manifest("pyproject.toml", packages(args, false), p -> p + " = \"*\"")
                    : verb.equals("pip") && args.size() > 0 && args.get(0).equals("install")
                    ? manifest("requirements.txt", packages(args.subList(1, args.size()), false), p -> p) : null;
            case "cargo" -> verb.equals("add") ? manifest("Cargo.toml", packages(args, false), p -> p + " = \"*\"") : null;
            case "go" -> verb.equals("get") ? manifest("go.mod", packages(args, false), p -> "require " + p) : null;
            case "gem" -> verb.equals("install") ? manifest("Gemfile", packages(args, false), p -> "gem \"" + p + "\"") : null;
            case "composer" -> verb.equals("require") ? manifest("composer.json", packages(args, false), p -> "\"" + p + "\": \"*\",") : null;
            case "docker" -> verb.equals("run") || verb.equals("pull")
                    ? manifest("docker-compose.yml", images(args), p -> "image: " + p) : null;
            default -> null;
        };
    }

    private static FileChange manifest(String path, List<String> names, java.util.function.Function<String, String> line) {
        List<FileChange.Line> lines = new ArrayList<>();
        for (String n : names) lines.add(new FileChange.Line(0, line.apply(n)));
        return new FileChange(path, lines);
    }

    /** Package names without versions; flags (and the values of -r / -e style flags) skipped. */
    static List<String> packages(List<String> args, boolean npm) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < args.size(); k++) {
            String a = args.get(k);
            if (a.startsWith("-")) {
                if (List.of("-r", "--requirement", "-c", "--constraint", "-e", "--editable", "--index-url", "-i", "--target").contains(a)) k++;
                continue;
            }
            if (a.startsWith(".") || a.startsWith("/") || a.contains("://") || a.startsWith("git+")) continue;
            String name = npm ? stripNpmVersion(a) : a.split("[=<>!~\\[;@ ]", 2)[0];
            if (!name.isBlank()) out.add(name);
        }
        return out;
    }

    private static List<String> images(List<String> args) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < args.size(); k++) {
            String a = args.get(k);
            if (a.startsWith("-")) {
                if (List.of("-p", "-v", "-e", "--name", "--env", "--publish", "--volume", "--network").contains(a)) k++;
                continue;
            }
            out.add(a.split("[:@]", 2)[0]);
            break;  // the first non-flag argument is the image; the rest is its command
        }
        return out;
    }

    /** "mongoose@8" → "mongoose"; "@apollo/server@4" → "@apollo/server". */
    private static String stripNpmVersion(String a) {
        int at = a.indexOf('@', a.startsWith("@") ? 1 : 0);
        return at > 0 ? a.substring(0, at) : a;
    }

    /** Whitespace split that keeps quoted strings together. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }
}
