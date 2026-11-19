package dev.notch.api.insights.core;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a candidate's {@link Read} from their skills and evidence. Pure and deterministic: the
 * same evidence and clock always give the same read, and every sentence it writes comes from a
 * count it can point to. Nothing is guessed, and AI history never leaves the owner's read.
 */
public final class Insights {

    /** A skill as scored (see SkillProfile): level is CLAIMED, SHOWN, VERIFIED or DEFENDED. */
    public record Skill(int id, String name, String domain, double score, String level, Integer topPercent) {
    }

    /**
     * One piece of evidence.
     *
     * @param kind     GITHUB_REPO, PROJECT or CHAT_EXPORT
     * @param skills   approved skill links
     * @param pending  skill links awaiting the candidate's approval (AI history)
     * @param weekdays own commits by weekday, Monday first, or null
     * @param hours    own commits by hour, or null
     */
    public record Work(UUID id, String kind, String title, int ownCommits, int activeWeeks, boolean fork, boolean duplicate,
                       Instant firstActive, Instant lastActive, Set<Integer> skills, Set<Integer> pending, boolean defended,
                       int[] weekdays, int[] hours, String chatSource, String chatMode, int chatConversations, String chatNote) {
        public Work {
            Objects.requireNonNull(kind, "kind");
            skills = skills == null ? Set.of() : Set.copyOf(skills);
            pending = pending == null ? Set.of() : Set.copyOf(pending);
        }

        boolean isRepo() {
            return "GITHUB_REPO".equals(kind) && !fork && !duplicate;
        }

        /** Repositories and projects: things built, not talked about. */
        boolean isBuilt() {
            return isRepo() || "PROJECT".equals(kind);
        }

        boolean isHistory() {
            return "CHAT_EXPORT".equals(kind);
        }
    }

    // ------------------------------------------------------------------ thresholds

    /** Below this many evidence-backed skills, or this much total score, a candidate is still an Explorer. */
    static final int EXPLORER_SKILLS = 3;
    static final double EXPLORER_WEIGHT = 90;
    /** A Polymath has no area above this share, and at least three areas at MIN_AREA_SHARE or more. */
    static final double POLYMATH_TOP_SHARE = 0.38;
    static final double MIN_AREA_SHARE = 0.15;
    /** Rhythm traits need this many commits to mean anything. */
    static final int RHYTHM_COMMITS = 20;
    static final double NIGHT_SHARE = 0.35;
    static final double EARLY_SHARE = 0.30;
    /** Weekends are 2/7 = 29% of days; 40% is clearly more. */
    static final double WEEKEND_SHARE = 0.40;
    static final double STEADY_SHARE = 0.5;
    static final int STEADY_MIN_WEEKS = 8;
    static final double SPRINT_COMMITS_PER_WEEK = 8;
    static final int FINISHER_REPOS = 3;
    static final int FINISHED_WEEKS = 4;
    /** A quiet strength: built with in this many repositories or projects, or with this many own commits. */
    static final int STRENGTH_PIECES = 2;
    static final int STRENGTH_COMMITS = 20;
    static final int MAX_PAIRS = 6;
    static final int MAX_LINKS = 80;
    static final int MAX_INSIGHTS = 4;
    static final long QUIET_DAYS = 75;

    private Insights() {
    }

    public static Read read(List<Skill> skills, List<Work> work, Map<Integer, String> skillNames, Instant now) {
        Objects.requireNonNull(now, "now");
        List<Skill> visible = skills.stream().sorted(Comparator.comparingDouble(Skill::score).reversed()).toList();
        Read.Rhythm rhythm = rhythm(work);
        Read.Stats stats = stats(visible, work);
        Read.Archetype archetype = archetype(visible, work, rhythm);
        return new Read(archetype, constellation(visible, work), saidDone(visible, work, skillNames),
                insights(visible, work, rhythm, now), stats, rhythm);
    }

    // ------------------------------------------------------------------ archetype

    enum Kind {
        DATA("The Data Storyteller", "You turn messy data into something people can see and act on.", "data and databases"),
        MODEL("The Model Builder", "You teach machines to find patterns, then check that they really did.", "AI and machine learning"),
        PRODUCT("The Product Builder", "You build the things people click on: interfaces, APIs, the whole loop.", "web and product"),
        SYSTEMS("The Systems Engineer", "You care about what happens after deploy: pipelines, containers, uptime.", "infrastructure"),
        MOBILE("The Mobile Maker", "You build for the screen in someone's pocket.", "mobile"),
        SOLVER("The Problem Solver", "You go deep on the fundamentals: algorithms, structures, the hard part.", "computer science fundamentals"),
        POLYMATH("The Polymath", "You don't stay in one lane, and your work shows range most people don't have.", null),
        EXPLORER("The Explorer", "You're just getting started. Every repository you add sharpens this picture.", null);

        final String title;
        final String line;
        final String area;

        Kind(String title, String line, String area) {
            this.title = title;
            this.line = line;
            this.area = area;
        }
    }

    /** How each catalogue domain counts towards an archetype. Languages span every kind, so they don't count. */
    static Map<Kind, Double> shares(String domain) {
        return switch (domain == null ? "" : domain) {
            case "Data" -> Map.of(Kind.DATA, 1.0);
            case "Databases" -> Map.of(Kind.DATA, 0.6, Kind.SYSTEMS, 0.4);
            case "AI & ML" -> Map.of(Kind.MODEL, 1.0);
            case "Web", "Design" -> Map.of(Kind.PRODUCT, 1.0);
            case "DevOps & Cloud" -> Map.of(Kind.SYSTEMS, 1.0);
            case "Mobile" -> Map.of(Kind.MOBILE, 1.0);
            case "CS Fundamentals" -> Map.of(Kind.SOLVER, 1.0);
            default -> Map.of();
        };
    }

    static Read.Archetype archetype(List<Skill> skills, List<Work> work, Read.Rhythm rhythm) {
        List<Skill> backed = skills.stream().filter(s -> !"CLAIMED".equals(s.level()) && s.score() > 0).toList();
        Map<Kind, Double> weight = new EnumMap<>(Kind.class);
        Map<Kind, List<Skill>> members = new EnumMap<>(Kind.class);
        double total = 0;
        for (Skill s : backed) {
            for (Map.Entry<Kind, Double> e : shares(s.domain()).entrySet()) {
                weight.merge(e.getKey(), s.score() * e.getValue(), Double::sum);
                members.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(s);
                total += s.score() * e.getValue();
            }
        }
        Read.Trait trait = trait(work, rhythm);
        double all = backed.stream().mapToDouble(Skill::score).sum();
        if (backed.size() < EXPLORER_SKILLS || all < EXPLORER_WEIGHT || total == 0) {
            String reason = backed.isEmpty() ? "No skills are backed by work yet."
                    : backed.size() + (backed.size() == 1 ? " skill is" : " skills are") + " backed by work so far.";
            return new Read.Archetype("EXPLORER", Kind.EXPLORER.title, Kind.EXPLORER.line, reason,
                    backed.stream().limit(3).map(Skill::name).toList(), trait);
        }
        final double sum = total;
        List<Map.Entry<Kind, Double>> ranked = weight.entrySet().stream()
                .sorted(Map.Entry.<Kind, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .toList();
        Kind top = ranked.get(0).getKey();
        double topShare = ranked.get(0).getValue() / sum;
        long areas = ranked.stream().filter(e -> e.getValue() / sum >= MIN_AREA_SHARE).count();
        if (topShare < POLYMATH_TOP_SHARE && areas >= 3) {
            List<String> named = ranked.stream().limit(3).map(e -> e.getKey().area).toList();
            List<String> picks = ranked.stream().limit(3)
                    .map(e -> members.get(e.getKey()).stream().max(Comparator.comparingDouble(Skill::score)).orElseThrow().name())
                    .toList();
            return new Read.Archetype("POLYMATH", Kind.POLYMATH.title, Kind.POLYMATH.line,
                    "Your proven skill is spread across " + named.get(0) + ", " + named.get(1) + " and " + named.get(2)
                            + ", with no area above " + percent(topShare) + ".",
                    picks, trait);
        }
        List<String> picks = members.get(top).stream().sorted(Comparator.comparingDouble(Skill::score).reversed())
                .map(Skill::name).distinct().limit(3).toList();
        return new Read.Archetype(top.name(), top.title, top.line,
                percent(topShare) + " of your proven skill is in " + top.area + ": " + listing(picks) + ".", picks, trait);
    }

    // ------------------------------------------------------------------ trait

    static Read.Trait trait(List<Work> work, Read.Rhythm rhythm) {
        record Candidate(double strength, Read.Trait trait) {
        }
        List<Candidate> found = new ArrayList<>();
        if (rhythm != null && rhythm.commits() >= RHYTHM_COMMITS) {
            double n = rhythm.commits();
            int[] h = rhythm.hours();
            int night = h[22] + h[23] + h[0] + h[1] + h[2] + h[3] + h[4];
            int early = h[5] + h[6] + h[7] + h[8];
            int weekend = rhythm.weekdays()[5] + rhythm.weekdays()[6];
            if (night / n >= NIGHT_SHARE) {
                found.add(new Candidate(night / n / NIGHT_SHARE, new Read.Trait("NIGHT_OWL", "Night Owl",
                        percent(night / n) + " of your commits land between 10 pm and 5 am.")));
            }
            if (early / n >= EARLY_SHARE) {
                found.add(new Candidate(early / n / EARLY_SHARE, new Read.Trait("EARLY_BIRD", "Early Bird",
                        percent(early / n) + " of your commits land before 9 am.")));
            }
            if (weekend / n >= WEEKEND_SHARE) {
                found.add(new Candidate(weekend / n / WEEKEND_SHARE, new Read.Trait("WEEKEND_BUILDER", "Weekend Builder",
                        percent(weekend / n) + " of your commits happen on Saturdays and Sundays.")));
            }
        }
        List<Work> repos = work.stream().filter(Work::isRepo).toList();
        int weeks = repos.stream().mapToInt(Work::activeWeeks).sum();
        int commits = repos.stream().mapToInt(Work::ownCommits).sum();
        Instant first = repos.stream().map(w -> w.firstActive() == null ? w.lastActive() : w.firstActive())
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant last = repos.stream().map(Work::lastActive).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        if (first != null && last != null && weeks >= STEADY_MIN_WEEKS) {
            double span = Math.max(1, Duration.between(first, last).toDays() / 7.0 + 1);
            double share = Math.min(1, weeks / span);
            if (share >= STEADY_SHARE) {
                found.add(new Candidate(share / STEADY_SHARE, new Read.Trait("STEADY", "Steady",
                        "You committed in " + weeks + " of the " + Math.round(span) + " weeks since your first repository here.")));
            }
        }
        if (weeks > 0 && commits >= RHYTHM_COMMITS) {
            double perWeek = (double) commits / weeks;
            if (perWeek >= SPRINT_COMMITS_PER_WEEK) {
                found.add(new Candidate(perWeek / SPRINT_COMMITS_PER_WEEK, new Read.Trait("SPRINTER", "Sprinter",
                        "When you work, you go all in: " + Math.round(perWeek) + " commits in an active week, on average.")));
            }
        }
        long finished = repos.stream().filter(w -> w.activeWeeks() >= FINISHED_WEEKS).count();
        if (finished >= FINISHER_REPOS) {
            found.add(new Candidate((double) finished / FINISHER_REPOS, new Read.Trait("FINISHER", "Finisher",
                    finished + " of your repositories were worked on for a month or more, not abandoned after a weekend.")));
        }
        return found.stream().max(Comparator.comparingDouble(Candidate::strength)).map(Candidate::trait).orElse(null);
    }

    // ------------------------------------------------------------------ constellation

    static Read.Constellation constellation(List<Skill> skills, List<Work> work) {
        List<Read.Star> stars = skills.stream()
                .map(s -> new Read.Star(s.id(), s.name(), s.domain(), round1(s.score()), s.level(), s.topPercent())).toList();
        Set<Integer> present = new java.util.HashSet<>();
        stars.forEach(s -> present.add(s.id()));
        Map<Long, Integer> pairs = new HashMap<>();
        for (Work w : work) {
            if (!w.isBuilt()) continue;
            List<Integer> ids = w.skills().stream().filter(present::contains).sorted().toList();
            for (int i = 0; i < ids.size(); i++) {
                for (int j = i + 1; j < ids.size(); j++) pairs.merge(((long) ids.get(i) << 32) | ids.get(j), 1, Integer::sum);
            }
        }
        List<Read.Link> links = pairs.entrySet().stream()
                .map(e -> new Read.Link((int) (e.getKey() >>> 32), (int) (long) e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(Read.Link::weight).reversed().thenComparingInt(Read.Link::a).thenComparingInt(Read.Link::b))
                .limit(MAX_LINKS)
                .toList();
        return new Read.Constellation(stars, links);
    }

    // ------------------------------------------------------------------ said vs done

    static Read.SaidDone saidDone(List<Skill> skills, List<Work> work, Map<Integer, String> names) {
        Map<Integer, String> name = new HashMap<>(names);
        skills.forEach(s -> name.putIfAbsent(s.id(), s.name()));
        // What was said: the strongest AI-history mention of each skill.
        Map<Integer, Work> said = new LinkedHashMap<>();
        for (Work w : work) {
            if (!w.isHistory()) continue;
            Set<Integer> ids = new java.util.HashSet<>(w.skills());
            ids.addAll(w.pending());
            for (int id : ids) said.merge(id, w, (a, b) -> modeRank(b) > modeRank(a) || b.chatConversations() > a.chatConversations() ? b : a);
        }
        // What was done: repositories and projects, with their own commits.
        record Done(int pieces, int commits, int repos) {
        }
        Map<Integer, Done> done = new HashMap<>();
        for (Work w : work) {
            if (!w.isBuilt()) continue;
            for (int id : w.skills()) {
                done.merge(id, new Done(1, w.ownCommits(), w.isRepo() ? 1 : 0),
                        (a, b) -> new Done(a.pieces() + b.pieces(), a.commits() + b.commits(), a.repos() + b.repos()));
            }
        }
        List<Read.Pair> edges = new ArrayList<>(), unproven = new ArrayList<>(), strengths = new ArrayList<>(), inSync = new ArrayList<>();
        Comparator<Integer> bySaid = Comparator.comparingInt((Integer id) -> -said.get(id).chatConversations())
                .thenComparingInt(id -> -modeRank(said.get(id)));
        for (int id : said.keySet().stream().sorted(bySaid).toList()) {
            Work w = said.get(id);
            Done d = done.get(id);
            String n = name.getOrDefault(id, "Skill " + id);
            if (d == null) {
                ("USED".equals(w.chatMode()) ? unproven : edges).add(new Read.Pair(id, n, saidText(w), "Nothing public shows it yet."));
            } else {
                inSync.add(new Read.Pair(id, n, saidText(w), doneText(d.pieces(), d.repos(), d.commits())));
            }
        }
        done.entrySet().stream()
                .filter(e -> !said.containsKey(e.getKey()))
                .filter(e -> e.getValue().pieces() >= STRENGTH_PIECES || e.getValue().commits() >= STRENGTH_COMMITS)
                .sorted(Comparator.comparingInt((Map.Entry<Integer, Done> e) -> -e.getValue().commits())
                        .thenComparingInt(e -> -e.getValue().pieces()).thenComparingInt(Map.Entry::getKey))
                .forEach(e -> strengths.add(new Read.Pair(e.getKey(), name.getOrDefault(e.getKey(), "Skill " + e.getKey()),
                        "Never came up in your AI history.", doneText(e.getValue().pieces(), e.getValue().repos(), e.getValue().commits()))));
        if (said.isEmpty() && strengths.isEmpty()) return null;
        return new Read.SaidDone(cap(edges), cap(unproven), cap(strengths), cap(inSync), !said.isEmpty());
    }

    private static int modeRank(Work w) {
        return switch (w.chatMode() == null ? "" : w.chatMode()) {
            case "USED" -> 3;
            case "LEARNING" -> 2;
            case "ASKED" -> 1;
            default -> 0;
        };
    }

    static String saidText(Work w) {
        String base;
        if ("MEMORY".equals(w.chatSource())) {
            base = switch (w.chatMode() == null ? "" : w.chatMode()) {
                case "USED" -> "Your AI remembers you using it.";
                case "LEARNING" -> "Your AI remembers you learning it.";
                default -> "Your AI remembers you asking about it.";
            };
        } else if (w.chatConversations() > 0) {
            base = "Came up in " + w.chatConversations() + " of your AI conversations.";
        } else {
            base = "Came up in your AI conversations.";
        }
        return w.chatNote() == null || w.chatNote().isBlank() ? base : base + " " + sentence(w.chatNote());
    }

    static String doneText(int pieces, int repos, int commits) {
        if (repos == 0) return pieces == 1 ? "Used in 1 project." : "Used in " + pieces + " projects.";
        String r = repos == 1 ? "1 repository" : repos + " repositories";
        String c = commits == 1 ? "1 commit" : commits + " commits";
        int projects = pieces - repos;
        return projects > 0 ? r + " (" + c + ") and " + (projects == 1 ? "1 project." : projects + " projects.") : r + ", " + c + ".";
    }

    // ------------------------------------------------------------------ insights

    static List<Read.Insight> insights(List<Skill> skills, List<Work> work, Read.Rhythm rhythm, Instant now) {
        record Scored(double weight, Read.Insight insight) {
        }
        List<Scored> out = new ArrayList<>();
        List<Work> repos = work.stream().filter(Work::isRepo).toList();

        // The skill they rank highest in.
        skills.stream().filter(s -> s.topPercent() != null)
                .min(Comparator.comparingInt(Skill::topPercent).thenComparing(Comparator.comparingDouble(Skill::score).reversed()))
                .ifPresent(s -> out.add(new Scored(10 - Math.log(s.topPercent()), new Read.Insight("TOP_SKILL",
                        "Top " + s.topPercent() + "% in " + s.name(),
                        "Your " + s.name() + " score of " + Math.round(s.score()) + " beats " + (100 - s.topPercent())
                                + "% of candidates on Notch with the same skill."))));

        // The deepest repository.
        repos.stream().filter(w -> w.activeWeeks() >= 3)
                .max(Comparator.comparingInt(Work::activeWeeks).thenComparingInt(Work::ownCommits))
                .ifPresent(w -> out.add(new Scored(6 + Math.min(3, w.activeWeeks() / 6.0), new Read.Insight("DEEPEST",
                        "Your deepest work is " + w.title(),
                        w.ownCommits() + " commits across " + w.activeWeeks() + " different weeks. Depth like this is what "
                                + "recruiters look for and rarely find."))));

        // Defended repositories.
        long defended = work.stream().filter(w -> w.isRepo() && w.defended()).count();
        if (defended > 0) {
            out.add(new Scored(9, new Read.Insight("DEFENDED",
                    defended == 1 ? "You defended a repository" : "You defended " + defended + " repositories",
                    "You explained your own code against the clock and passed. Few candidates have this, and a defended "
                            + "repository can take a skill past 85 on its own.")));
        }

        // Growth: repositories started this year against the year before.
        long thisYear = repos.stream().filter(w -> started(w) != null && started(w).isAfter(now.minus(Duration.ofDays(365)))).count();
        long lastYear = repos.stream().filter(w -> started(w) != null && !started(w).isAfter(now.minus(Duration.ofDays(365)))
                && started(w).isAfter(now.minus(Duration.ofDays(730)))).count();
        if (thisYear >= 2 && thisYear > lastYear) {
            out.add(new Scored(7 + Math.min(2, thisYear - lastYear) / 2.0, new Read.Insight("GROWTH",
                    "You're building more",
                    "You started " + thisYear + " repositories in the last 12 months"
                            + (lastYear == 0 ? ", and none in the year before." : ", up from " + lastYear + " in the year before."))));
        }

        // Rhythm: favourite day and hour.
        if (rhythm != null && rhythm.commits() >= RHYTHM_COMMITS) {
            int day = argmax(rhythm.weekdays());
            double dayShare = (double) rhythm.weekdays()[day] / rhythm.commits();
            if (dayShare >= 0.25) {
                String d = DayOfWeek.of(day + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                out.add(new Scored(5 + dayShare * 4, new Read.Insight("DAY", d + " is your day",
                        percent(dayShare) + " of your commits land on a " + d + ", more than any other day.")));
            }
            int hour = argmax(rhythm.hours());
            double hourShare = (double) rhythm.hours()[hour] / rhythm.commits();
            if (hourShare >= 0.12) {
                out.add(new Scored(4 + hourShare * 6, new Read.Insight("HOUR", "Your hour is " + clock(hour),
                        "More of your commits happen between " + clock(hour) + " and " + clock((hour + 1) % 24)
                                + " than in any other hour.")));
            }
        }

        // A quiet spell: scores fade with a 12-month half-life, so say so while it's cheap to fix.
        repos.stream().map(Work::lastActive).filter(Objects::nonNull).max(Comparator.naturalOrder()).ifPresent(last -> {
            long days = Duration.between(last, now).toDays();
            if (days >= QUIET_DAYS) {
                out.add(new Scored(8, new Read.Insight("QUIET", "It's been " + (days / 30) + " months since your last commit",
                        "Scores fade slowly as work gets older. One small push to any repository brings them back up.")));
            }
        });

        // Breadth backed by their own code.
        long verified = skills.stream().filter(s -> "VERIFIED".equals(s.level()) || "DEFENDED".equals(s.level())).count();
        if (verified >= 3) {
            out.add(new Scored(3 + Math.min(3, verified / 3.0), new Read.Insight("VERIFIED",
                    verified + " skills verified by your own commits",
                    "Each one is backed by a repository you worked on across at least two weeks, not just a line on a resume.")));
        }

        return out.stream().sorted(Comparator.comparingDouble(Scored::weight).reversed()).limit(MAX_INSIGHTS)
                .map(Scored::insight).toList();
    }

    // ------------------------------------------------------------------ stats and rhythm

    static Read.Stats stats(List<Skill> skills, List<Work> work) {
        List<Work> repos = work.stream().filter(Work::isRepo).toList();
        Instant since = work.stream().filter(Work::isBuilt).map(w -> started(w) == null ? w.lastActive() : started(w))
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        return new Read.Stats(repos.size(), repos.stream().mapToInt(Work::ownCommits).sum(),
                repos.stream().mapToInt(Work::activeWeeks).sum(),
                (int) skills.stream().filter(s -> !"CLAIMED".equals(s.level()) && s.score() > 0).count(),
                (int) repos.stream().filter(Work::defended).count(), since);
    }

    static Read.Rhythm rhythm(List<Work> work) {
        int[] days = new int[7];
        int[] hours = new int[24];
        int n = 0;
        for (Work w : work) {
            if (!w.isRepo() || w.weekdays() == null || w.hours() == null) continue;
            for (int i = 0; i < 7; i++) days[i] += w.weekdays()[i];
            for (int i = 0; i < 24; i++) hours[i] += w.hours()[i];
            for (int d : w.weekdays()) n += d;
        }
        return n == 0 ? null : new Read.Rhythm(days, hours, n);
    }

    // ------------------------------------------------------------------ helpers

    private static Instant started(Work w) {
        return w.firstActive();
    }

    private static <T> List<T> cap(List<T> l) {
        return List.copyOf(l.subList(0, Math.min(MAX_PAIRS, l.size())));
    }

    static String percent(double share) {
        return Math.round(share * 100) + "%";
    }

    static String clock(int hour) {
        int h = hour % 12 == 0 ? 12 : hour % 12;
        return h + (hour < 12 ? " am" : " pm");
    }

    static String listing(List<String> items) {
        if (items.size() <= 1) return items.isEmpty() ? "" : items.get(0);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    private static String sentence(String note) {
        String n = note.strip();
        if (n.isEmpty()) return n;
        n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
        return n.endsWith(".") || n.endsWith("!") || n.endsWith("?") ? n : n + ".";
    }

    private static int argmax(int[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[best]) best = i;
        return best;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
