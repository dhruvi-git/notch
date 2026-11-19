package dev.notch.api.portfolio.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what the GitHub API says about one repository into a piece of evidence: the candidate's
 * own commits, the distinct weeks they committed in, when they were last active, and the
 * skills the repository shows.
 *
 * <p>Skills come from four places: languages that make up at least {@link #MIN_LANGUAGE_SHARE}
 * of the code (or the main language), the repository's topics, its name and its description.
 * Each is matched against the catalogue, so only known skills are recorded.
 */
public final class RepoEvidence {

    /** What the API returned for one repository. {@code ownCommitDates} are the candidate's commits only. */
    public record RepoData(String fullName, String htmlUrl, String description, List<String> topics,
                           Map<String, Long> languages, boolean fork, List<Instant> ownCommitDates,
                           Instant pushedAt, String rootSha, String treeSha) {
        public RepoData {
            Objects.requireNonNull(fullName, "fullName");
            topics = topics == null ? List.of() : List.copyOf(topics);
            languages = languages == null ? Map.of() : Map.copyOf(languages);
            ownCommitDates = ownCommitDates == null ? List.of() : List.copyOf(ownCommitDates);
        }
    }

    public record Draft(String title, String url, String description, int ownCommits, int activeWeeks, Instant lastActive,
                        Set<Integer> skillIds, String rootSha, String treeSha, Rhythm rhythm) {
        public Draft(String title, String url, String description, int ownCommits, int activeWeeks, Instant lastActive,
                     Set<Integer> skillIds, String rootSha, String treeSha) {
            this(title, url, description, ownCommits, activeWeeks, lastActive, skillIds, rootSha, treeSha, null);
        }
    }

    /**
     * When the candidate's own commits happened: the first one, and counts by weekday (Monday
     * first) and by hour, in the candidate's zone. Counts only; the commits are not kept.
     */
    public record Rhythm(Instant firstActive, int[] weekdays, int[] hours) {
        public Rhythm {
            if (weekdays.length != 7 || hours.length != 24) throw new IllegalArgumentException("7 weekdays and 24 hours");
            weekdays = weekdays.clone();
            hours = hours.clone();
        }
    }

    /** Commit times are bucketed in this zone unless the API is configured otherwise; Notch starts with students in India. */
    public static final java.time.ZoneId DEFAULT_ZONE = java.time.ZoneId.of("Asia/Kolkata");

    public enum Skip { FORK, NO_OWN_COMMITS }

    public static final double MIN_LANGUAGE_SHARE = 0.10;

    private static final Pattern LAST_PAGE = Pattern.compile("[?&]page=(\\d+)[^>]*>;\\s*rel=\"last\"");

    private RepoEvidence() {
    }

    /** Why a repository is left out, if it is: forks and repos without the candidate's own commits prove nothing. */
    public static Optional<Skip> skip(RepoData repo) {
        if (repo.fork()) return Optional.of(Skip.FORK);
        if (repo.ownCommitDates().isEmpty()) return Optional.of(Skip.NO_OWN_COMMITS);
        return Optional.empty();
    }

    public static Draft build(RepoData repo, SkillCatalog catalog) {
        return build(repo, catalog, DEFAULT_ZONE);
    }

    public static Draft build(RepoData repo, SkillCatalog catalog, java.time.ZoneId zone) {
        if (skip(repo).isPresent()) throw new IllegalArgumentException("repository is skipped: " + skip(repo).get());
        Instant last = repo.ownCommitDates().stream().max(Comparator.naturalOrder()).orElse(repo.pushedAt());
        String name = repo.fullName().substring(repo.fullName().indexOf('/') + 1);
        return new Draft(name, repo.htmlUrl(), blankToNull(repo.description()), repo.ownCommitDates().size(),
                activeWeeks(repo.ownCommitDates()), last, skills(repo, catalog), repo.rootSha(), repo.treeSha(),
                rhythm(repo.ownCommitDates(), zone));
    }

    /** The rhythm of a list of commit times, or null when there are none. */
    public static Rhythm rhythm(List<Instant> commits, java.time.ZoneId zone) {
        if (commits.isEmpty()) return null;
        int[] days = new int[7];
        int[] hours = new int[24];
        Instant first = commits.get(0);
        for (Instant c : commits) {
            java.time.ZonedDateTime t = c.atZone(zone);
            days[t.getDayOfWeek().getValue() - 1]++;
            hours[t.getHour()]++;
            if (c.isBefore(first)) first = c;
        }
        return new Rhythm(first, days, hours);
    }

    static Set<Integer> skills(RepoData repo, SkillCatalog catalog) {
        Set<Integer> out = new LinkedHashSet<>();
        long total = repo.languages().values().stream().mapToLong(Long::longValue).sum();
        String main = repo.languages().entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        for (Map.Entry<String, Long> lang : repo.languages().entrySet()) {
            boolean big = total > 0 && (double) lang.getValue() / total >= MIN_LANGUAGE_SHARE;
            if (big || lang.getKey().equals(main)) catalog.resolve(lang.getKey()).ifPresent(s -> out.add(s.id()));
        }
        for (String topic : repo.topics()) catalog.resolve(topic.replace('-', ' ')).ifPresent(s -> out.add(s.id()));
        String name = repo.fullName().substring(repo.fullName().indexOf('/') + 1).replace('-', ' ').replace('_', ' ');
        for (SkillCatalog.Skill s : catalog.extract(name + "\n" + (repo.description() == null ? "" : repo.description()))) {
            out.add(s.id());
        }
        return out;
    }

    /** Distinct ISO weeks (in UTC) that contain at least one commit. */
    public static int activeWeeks(List<Instant> commits) {
        Set<Long> weeks = new HashSet<>();
        for (Instant c : commits) {
            var d = c.atZone(ZoneOffset.UTC).toLocalDate();
            weeks.add(d.get(IsoFields.WEEK_BASED_YEAR) * 100L + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        }
        return weeks.size();
    }

    /** The number of the last page in a GitHub {@code Link} header, or empty when there is only one page. */
    public static OptionalInt lastPage(String linkHeader) {
        if (linkHeader == null) return OptionalInt.empty();
        Matcher m = LAST_PAGE.matcher(linkHeader);
        return m.find() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
