package dev.notch.api.recruit.core;

import dev.notch.api.portfolio.core.SkillCatalog;
import dev.notch.engine.score.SkillScoring.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a pasted job description into draft requirements the recruiter then edits.
 *
 * <p>The text is split into lines and sentences. Each skill the catalogue finds in a sentence
 * takes that sentence's strength: a "must"/"required" sentence makes it a must-have (weight 3,
 * verified evidence expected), a "nice to have"/"bonus" sentence a nice-to-have (weight 1),
 * anything else a normal requirement (weight 2). When a skill appears in several sentences the
 * strongest wins. This is deliberately plain and predictable: the recruiter sees and can change
 * every requirement before any matching runs.
 */
public final class JobParser {

    public record DraftRequirement(SkillCatalog.Skill skill, double weight, boolean mustHave, Level minLevel) {
    }

    public static final int MAX_REQUIREMENTS = 15;

    private static final Pattern SPLIT = Pattern.compile("[\\n\\r;•]+|(?<=[.!?])\\s+");
    private static final Pattern NICE = Pattern.compile(
            "\\b(nice to have|good to have|bonus|a plus|is a plus|preferred|familiarity|exposure to|optional)\\b");
    private static final Pattern MUST = Pattern.compile(
            "\\b(must|required|requirement|requirements|mandatory|essential|need|needs|strong|proficient|proficiency|expert)\\b");

    private enum Strength { NICE, NORMAL, MUST }

    private JobParser() {
    }

    public static List<DraftRequirement> parse(String description, SkillCatalog catalog) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("the job description is empty");
        }
        Map<Integer, Strength> strongest = new LinkedHashMap<>();
        for (String sentence : SPLIT.split(description)) {
            List<SkillCatalog.Skill> skills = catalog.extract(sentence);
            if (skills.isEmpty()) continue;
            String lower = sentence.toLowerCase(Locale.ROOT);
            Strength s = NICE.matcher(lower).find() ? Strength.NICE
                    : MUST.matcher(lower).find() ? Strength.MUST : Strength.NORMAL;
            for (SkillCatalog.Skill skill : skills) strongest.merge(skill.id(), s, (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
        List<DraftRequirement> out = new ArrayList<>();
        for (Map.Entry<Integer, Strength> e : strongest.entrySet()) {
            SkillCatalog.Skill skill = catalog.get(e.getKey()).orElseThrow();
            out.add(switch (e.getValue()) {
                case MUST -> new DraftRequirement(skill, 3, true, Level.VERIFIED);
                case NORMAL -> new DraftRequirement(skill, 2, false, Level.SHOWN);
                case NICE -> new DraftRequirement(skill, 1, false, Level.SHOWN);
            });
        }
        // Keep the most important when a description lists too many.
        out.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        return out.size() > MAX_REQUIREMENTS ? List.copyOf(out.subList(0, MAX_REQUIREMENTS)) : List.copyOf(out);
    }
}
