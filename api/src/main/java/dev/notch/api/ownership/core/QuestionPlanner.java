package dev.notch.api.ownership.core;

import dev.notch.api.ownership.core.RegionExtractor.Region;
import dev.notch.api.portfolio.core.SkillCatalog;
import dev.notch.engine.cover.GreedySetCover;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses which pieces of a candidate's code to ask about, and what to ask.
 *
 * <p>A good check touches every skill the repository claims, every kind of decision the code
 * makes, and its main files, in as few questions and as little reading as possible. That is a
 * weighted set cover: the elements are those skills, concepts and files; each code region covers
 * the ones it shows; a region costs more the longer it is to read. {@link GreedySetCover} picks
 * the regions within a budget of questions. Each picked region is then asked about the most
 * revealing concept it adds to the check.
 */
public final class QuestionPlanner {

    /**
     * One planned question.
     *
     * @param rubric      groups of interchangeable terms a good answer should use
     * @param identifiers names from this code; using them shows the answer is about this code
     * @param covers      the skills, concepts and files this question was chosen to cover
     */
    public record Planned(Region region, Concept concept, String prompt, List<List<String>> rubric,
                          List<String> identifiers, List<String> skillNames, List<String> covers) {
    }

    /** The plan and how well it covers what the check set out to touch. */
    public record Plan(List<Planned> questions, int elements, int covered, List<String> uncovered) {
    }

    public static final int DEFAULT_QUESTIONS = 5;
    static final int MAX_FILES = 4;
    static final double LINES_PER_COST_UNIT = 40.0;

    private QuestionPlanner() {
    }

    /**
     * @param focusSkills the skills this repository is evidence for; the check should touch each
     * @param maxQuestions the budget of questions
     */
    public static Plan plan(List<Region> regions, Set<Integer> focusSkills, SkillCatalog catalog, int maxQuestions) {
        if (maxQuestions < 1) throw new IllegalArgumentException("a check needs at least one question");
        if (regions.isEmpty()) return new Plan(List.of(), 0, 0, List.of());

        // Elements: the focus skills that appear in some region, every concept that appears, and the main files.
        Map<String, Integer> element = new LinkedHashMap<>();
        for (Region r : regions) for (int s : r.skills()) if (focusSkills.contains(s)) element.putIfAbsent("skill:" + s, element.size());
        for (Region r : regions) for (Concept c : r.concepts()) element.putIfAbsent("concept:" + c.name(), element.size());
        Map<String, Integer> fileLines = new HashMap<>();
        for (Region r : regions) fileLines.merge(r.path(), r.lines(), Integer::sum);
        fileLines.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_FILES).forEach(e -> element.putIfAbsent("file:" + e.getKey(), element.size()));

        List<BitSet> sets = new ArrayList<>();
        double[] costs = new double[regions.size()];
        for (int i = 0; i < regions.size(); i++) {
            Region r = regions.get(i);
            BitSet b = new BitSet(element.size());
            for (int s : r.skills()) set(b, element, "skill:" + s);
            for (Concept c : r.concepts()) set(b, element, "concept:" + c.name());
            set(b, element, "file:" + r.path());
            sets.add(b);
            costs[i] = 1.0 + Math.min(r.lines(), RegionExtractor.MAX_SHOWN_LINES) / LINES_PER_COST_UNIT;
        }
        GreedySetCover.Result cover = GreedySetCover.solve(element.size(), sets, costs, maxQuestions);

        List<Integer> picked = new ArrayList<>(cover.picked());
        // A tiny repository may be covered by one region; still ask a few questions, about the richest regions left.
        int want = Math.min(Math.min(3, maxQuestions), regions.size());
        if (picked.size() < want) {
            List<Integer> rest = new ArrayList<>();
            for (int i = 0; i < regions.size(); i++) if (!picked.contains(i)) rest.add(i);
            rest.sort(Comparator.<Integer>comparingInt(i -> -regions.get(i).concepts().size()).thenComparingInt(i -> i));
            for (int i : rest) {
                if (picked.size() >= want) break;
                picked.add(i);
            }
        }

        String[] names = new String[element.size()];
        element.forEach((k, v) -> names[v] = describe(k, catalog));
        EnumSet<Concept> asked = EnumSet.noneOf(Concept.class);
        BitSet seen = new BitSet(element.size());
        List<Planned> out = new ArrayList<>();
        for (int i : picked) {
            Region r = regions.get(i);
            Concept concept = null;
            for (Concept c : Concept.values()) {
                if (r.concepts().contains(c) && !asked.contains(c)) {
                    concept = c;
                    break;
                }
            }
            if (concept == null) concept = r.concepts().stream().findFirst().orElse(null);
            if (concept != null) asked.add(concept);

            BitSet adds = (BitSet) sets.get(i).clone();
            adds.andNot(seen);
            seen.or(sets.get(i));
            List<String> covers = adds.stream().mapToObj(e -> names[e]).toList();

            String lines = r.startLine() + "–" + r.endLine();
            String template = concept == null ? Concept.GENERAL_PROMPT : concept.prompt();
            String prompt = template.replace("{name}", r.name()).replace("{lines}", lines + " of " + r.path());
            List<List<String>> rubric = concept == null ? Concept.GENERAL_RUBRIC : concept.rubric();
            List<String> skillNames = r.skills().stream().map(s -> catalog.get(s).map(SkillCatalog.Skill::name).orElse(null))
                    .filter(java.util.Objects::nonNull).toList();
            out.add(new Planned(r, concept, prompt, rubric, r.identifiers().stream().limit(4).toList(), skillNames, covers));
        }

        List<String> uncovered = new ArrayList<>();
        BitSet missing = new BitSet(element.size());
        missing.set(0, element.size());
        missing.andNot(seen);
        missing.stream().forEach(e -> uncovered.add(names[e]));
        return new Plan(Collections.unmodifiableList(out), element.size(), seen.cardinality(), List.copyOf(uncovered));
    }

    private static void set(BitSet b, Map<String, Integer> element, String key) {
        Integer i = element.get(key);
        if (i != null) b.set(i);
    }

    private static String describe(String key, SkillCatalog catalog) {
        int colon = key.indexOf(':');
        String kind = key.substring(0, colon), value = key.substring(colon + 1);
        return switch (kind) {
            case "skill" -> catalog.get(Integer.parseInt(value)).map(SkillCatalog.Skill::name).orElse(value);
            case "concept" -> Concept.valueOf(value).label();
            default -> value;
        };
    }
}
