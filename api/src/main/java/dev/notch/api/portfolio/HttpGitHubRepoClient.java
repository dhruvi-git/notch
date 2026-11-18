package dev.notch.api.portfolio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.notch.api.portfolio.core.RepoEvidence;
import dev.notch.api.portfolio.core.RepoEvidence.RepoData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * GitHub's REST API with the JDK's HTTP client, public data only. Four calls per repository:
 * languages, the candidate's own commits (up to 100), the latest commit (its tree is the
 * copy fingerprint) and the first commit (found through the last page of a one-per-page
 * listing). Without a token GitHub allows 60 calls an hour, so a sync reads at most 12
 * repositories; with {@code GITHUB_TOKEN} set, 5,000 an hour and up to 30 repositories.
 */
@Component
public class HttpGitHubRepoClient implements GitHubRepoClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repo(@JsonProperty("full_name") String fullName, @JsonProperty("html_url") String htmlUrl, String description,
                Boolean fork, @JsonProperty("pushed_at") String pushedAt, List<String> topics, Integer size) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Commit(String sha, CommitBody commit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitBody(Person author, Tree tree) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Person(String date) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Tree(String sha) {
    }

    private record Response(int status, String body, String link, String remaining) {
    }

    private static final String API = "https://api.github.com";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String token;

    public HttpGitHubRepoClient(ObjectMapper mapper, @Value("${GITHUB_TOKEN:}") String token) {
        this.mapper = mapper;
        this.token = token == null ? "" : token.strip();
    }

    @Override
    public boolean authenticated() {
        return !token.isEmpty();
    }

    @Override
    public Listing fetch(String username, int maxRepos) {
        Response list = get("/users/" + enc(username) + "/repos?type=owner&sort=pushed&per_page=100");
        if (list.status() == 404) throw new UserNotFound(username);
        check(list);
        Repo[] repos = mapper.readValue(list.body(), Repo[].class);

        List<String> listed = new ArrayList<>();
        List<RepoData> out = new ArrayList<>();
        int forks = 0;
        boolean partial = false;
        for (Repo r : repos) {
            listed.add(r.htmlUrl());
            if (Boolean.TRUE.equals(r.fork())) {
                forks++;
                continue;
            }
            if (r.size() != null && r.size() == 0) continue; // empty repository
            if (out.size() >= maxRepos || partial) continue;
            try {
                out.add(details(r, username));
            } catch (RateLimited e) {
                partial = true;
            }
        }
        return new Listing(out, listed, forks, partial);
    }

    private RepoData details(Repo r, String username) {
        String base = "/repos/" + r.fullName();

        Response langs = get(base + "/languages");
        check(langs);
        Map<String, Long> languages = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = mapper.readValue(langs.body(), Map.class);
        raw.forEach((k, v) -> {
            if (v instanceof Number n) languages.put(k, n.longValue());
        });

        List<Instant> own = new ArrayList<>();
        for (Commit c : commits(base + "/commits?author=" + enc(username) + "&per_page=100")) {
            if (c.commit() != null && c.commit().author() != null && c.commit().author().date() != null) {
                own.add(Instant.parse(c.commit().author().date()));
            }
        }

        String treeSha = null, rootSha = null;
        Response head = get(base + "/commits?per_page=1");
        if (head.status() != 409) { // 409: the repository has no commits
            check(head);
            Commit[] latest = mapper.readValue(head.body(), Commit[].class);
            if (latest.length > 0) {
                treeSha = latest[0].commit() == null || latest[0].commit().tree() == null ? null : latest[0].commit().tree().sha();
                rootSha = latest[0].sha();
                OptionalInt last = RepoEvidence.lastPage(head.link());
                if (last.isPresent()) {
                    Response first = get(base + "/commits?per_page=1&page=" + last.getAsInt());
                    check(first);
                    Commit[] oldest = mapper.readValue(first.body(), Commit[].class);
                    if (oldest.length > 0) rootSha = oldest[0].sha();
                }
            }
        }
        Instant pushed = r.pushedAt() == null ? Instant.now() : Instant.parse(r.pushedAt());
        return new RepoData(r.fullName(), r.htmlUrl(), r.description(), r.topics(), languages, false, own, pushed, rootSha, treeSha);
    }

    private List<Commit> commits(String path) {
        Response res = get(path);
        if (res.status() == 409) return List.of();
        check(res);
        return List.of(mapper.readValue(res.body(), Commit[].class));
    }

    private void check(Response r) {
        // GitHub signals an exhausted limit with 429, or 403 and no calls remaining; other 403s are real refusals.
        if (r.status() == 429 || (r.status() == 403 && "0".equals(r.remaining()))) throw new RateLimited();
        if (r.status() >= 400) throw new IllegalStateException("GitHub returned HTTP " + r.status());
    }

    private Response get(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "notch-api")
                .GET();
        if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body(), r.headers().firstValue("link").orElse(null),
                    r.headers().firstValue("x-ratelimit-remaining").orElse(null));
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach GitHub", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling GitHub", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
