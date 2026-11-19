package dev.notch.api.insights.core;

import dev.notch.api.portfolio.core.SkillCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bringing in what a candidate's AI assistant already knows about them, without an export file:
 * Notch gives them {@link #PROMPT} to paste into ChatGPT or Claude, they paste the reply back,
 * and {@link #parse} reads it. Nothing in the reply counts until the candidate approves it.
 *
 * <p>The reply format is plain lines, so it survives the markdown, bold text, code fences and
 * bullet styles assistants add. Skills are matched against Notch's catalogue; anything else is
 * reported as not recognised rather than guessed at.
 */
public final class MemoryImport {

    public static final String PROMPT = """
            I'm building my profile on Notch, a portfolio for students. Using only what you remember from our \
            past conversations, fill in the template below. Don't guess or invent anything: if you don't know, \
            leave that section empty. Keep my wording where you can.

            For each skill, choose one word: USED (I built something with it), LEARNING (I'm studying it), or \
            ASKED (I asked you about it). Add one short line saying what you remember.

            Reply with the template only, exactly in this format:

            NOTCH PROFILE
            SKILLS
            - skill name | USED or LEARNING or ASKED | what you remember, in one line
            PROJECTS
            - project name | what it does, in one line | skills used, separated by commas
            GOAL
            - the role or job I'm aiming for, if I've told you
            END""";

    public enum Mode { USED, LEARNING, ASKED }

    /** @param note what the assistant remembered, trimmed to 200 characters, or null */
    public record SkillLine(SkillCatalog.Skill skill, Mode mode, String note) {
    }

    public record ProjectLine(String title, String description, List<SkillCatalog.Skill> skills) {
    }

    /**
     * @param unrecognised skill names that aren't in Notch's catalogue (shown to the candidate, not stored)
     * @param goal         the role they're aiming for, or null
     */
    public record Parsed(List<SkillLine> skills, List<ProjectLine> projects, List<String> unrecognised, String goal) {
        public boolean isEmpty() {
            return skills.isEmpty() && projects.isEmpty() && goal == null;
        }
    }

    public static final int MAX_TEXT = 20_000;
    public static final int MAX_SKILLS = 30;
    public static final int MAX_PROJECTS = 10;
    static final int MAX_NOTE = 200;
    static final int MAX_TITLE = 120;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_GOAL = 80;

    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•–]|\\d+[.)])\\s+");
    /** Markdown that assistants add around lines: bold, code, headings and quotes. "C#" and snake_case survive. */
    private static final Pattern NOISE = Pattern.compile("\\*\\*|__|`+|^\\s*(?:#{1,6}|>)\\s*");
    private static final Pattern EMPTY = Pattern.compile("(?i)^(none|n/?a|nothing|-|unknown|not sure|\\(empty\\)|empty)\\.?$");

    private enum Section { NONE, SKILLS, PROJECTS, GOAL }

    private MemoryImport() {
    }

    /**
     * Reads a pasted reply.
     *
     * @throws IllegalArgumentException when the text is empty, too long, or not a reply to the prompt
     */
    public static Parsed parse(String text, SkillCatalog catalog) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Paste the reply from your AI assistant.");
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("That's longer than a reply to the prompt (20,000 characters at most).");
        Section section = Section.NONE;
        boolean sawHeader = false, sawSection = false;
        Map<Integer, SkillLine> skills = new LinkedHashMap<>();
        Set<String> unrecognised = new LinkedHashSet<>();
        List<ProjectLine> projects = new ArrayList<>();
        Set<String> projectTitles = new LinkedHashSet<>();
        String goal = null;

        for (String raw : text.replace("\r", "").split("\n")) {
            String line = clean(raw);
            if (line.isEmpty() || line.startsWith("```")) continue;
            String upper = line.toUpperCase(Locale.ROOT).replaceAll("[:.]+$", "").strip();
            if (upper.equals("NOTCH PROFILE") || upper.equals("NOTCH-PROFILE")) {
                sawHeader = true;
                continue;
            }
            if (upper.equals("END")) break;
            Optional<Section> heading = heading(upper);
            if (heading.isPresent()) {
                section = heading.get();
                sawSection = true;
                continue;
            }
            boolean bullet = BULLET.matcher(raw).find();
            String body = BULLET.matcher(line).replaceFirst("").strip();
            if (body.isEmpty() || EMPTY.matcher(body).matches()) continue;
            switch (section) {
                case SKILLS -> {
                    if (!bullet && !body.contains("|")) continue;
                    String[] parts = split(body);
                    Optional<SkillCatalog.Skill> skill = resolve(parts[0], catalog);
                    if (skill.isEmpty()) {
                        if (!parts[0].isBlank() && unrecognised.size() < 20) unrecognised.add(trim(parts[0], 40));
                        continue;
                    }
                    Mode mode = mode(parts.length > 1 ? parts[1] : "");
                    String note = parts.length > 2 ? blankToNull(trim(String.join(" | ", java.util.Arrays.copyOfRange(parts, 2, parts.length)), MAX_NOTE)) : null;
                    SkillLine next = new SkillLine(skill.get(), mode, note);
                    // The same skill twice: keep the strongest relationship (used > learning > asked).
                    skills.merge(skill.get().id(), next, (a, b) -> b.mode().ordinal() < a.mode().ordinal() ? b : a);
                }
                case PROJECTS -> {
                    if (!bullet && !body.contains("|")) continue;
                    if (projects.size() >= MAX_PROJECTS) continue;
                    String[] parts = split(body);
                    String title = trim(parts[0], MAX_TITLE);
                    if (title.isBlank() || !projectTitles.add(title.toLowerCase(Locale.ROOT))) continue;
                    String description = parts.length > 1 ? blankToNull(trim(parts[1], MAX_DESCRIPTION)) : null;
                    List<SkillCatalog.Skill> used = new ArrayList<>();
                    if (parts.length > 2) {
                        for (String name : parts[2].split("[,;/]")) {
                            resolve(name, catalog).filter(s -> used.stream().noneMatch(u -> u.id() == s.id())).ifPresent(used::add);
                        }
                    }
                    // Skills named only in the description still count as used in the project.
                    if (description != null) {
                        for (SkillCatalog.Skill s : catalog.extract(description)) {
                            if (used.stream().noneMatch(u -> u.id() == s.id())) used.add(s);
                        }
                    }
                    projects.add(new ProjectLine(title, description, List.copyOf(used)));
                }
                case GOAL -> {
                    if (goal == null) goal = trim(body.replaceAll("\\s*\\|.*$", ""), MAX_GOAL);
                }
                case NONE -> {
                }
            }
        }
        if (!sawHeader && !sawSection) {
            throw new IllegalArgumentException("This doesn't look like a reply to Notch's prompt. Copy the prompt again, "
                    + "paste it into ChatGPT or Claude, and paste back the whole reply.");
        }
        List<SkillLine> skillList = skills.values().stream().limit(MAX_SKILLS).toList();
        return new Parsed(skillList, List.copyOf(projects), List.copyOf(unrecognised), blankToNull(goal));
    }

    private static Optional<Section> heading(String upper) {
        return switch (upper) {
            case "SKILLS", "SKILL" -> Optional.of(Section.SKILLS);
            case "PROJECTS", "PROJECT" -> Optional.of(Section.PROJECTS);
            case "GOAL", "GOALS" -> Optional.of(Section.GOAL);
            default -> Optional.empty();
        };
    }

    static Mode mode(String word) {
        String w = word.strip().toUpperCase(Locale.ROOT);
        if (w.startsWith("USE") || w.startsWith("BUIL")) return Mode.USED;
        if (w.startsWith("LEARN") || w.startsWith("STUD")) return Mode.LEARNING;
        return Mode.ASKED;
    }

    /** A skill by its exact name or alias, or else the single catalogue skill the text names. */
    static Optional<SkillCatalog.Skill> resolve(String name, SkillCatalog catalog) {
        String n = name.strip().replaceAll("\\s*\\(.*\\)$", "");
        if (n.isEmpty() || n.length() > 60) return Optional.empty();
        Optional<SkillCatalog.Skill> exact = catalog.resolve(n);
        if (exact.isPresent()) return exact;
        List<SkillCatalog.Skill> found = catalog.extract(n);
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    private static String[] split(String body) {
        String[] parts = body.split("\\s*\\|\\s*");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].strip();
        return parts;
    }

    private static String clean(String raw) {
        return NOISE.matcher(raw).replaceAll("").strip();
    }

    private static String trim(String s, int max) {
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max - 1).strip() + "…";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
