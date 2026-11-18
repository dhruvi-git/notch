package dev.notch.api.ownership;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An Ownership Check as the API returns it. Rubrics are never sent. While a check is open its
 * owner sees the questions and their own draft answers; once finished, scores, feedback and
 * integrity signals appear. The public transcript (a finished check on a public profile) shows
 * the same, with the owner's handle.
 *
 * @param secondsLeft     time left while open; 0 once finished
 * @param questionsBy     "model" when a language model wrote the questions, "templates" otherwise
 * @param gradedBy        "model", "rubric" or "mixed"; null until finished
 * @param planElements    skills, concepts and files the check set out to touch
 * @param planCovered     how many of those the questions touch, chosen by greedy set cover
 */
public record CheckView(UUID id, UUID evidenceId, String handle, String repo, String repoUrl, String status,
                        Instant startedAt, Instant deadline, Instant finishedAt, long secondsLeft, Integer score,
                        boolean defended, String summary, String questionsBy, String gradedBy, int planElements,
                        int planCovered, List<Question> questions) {

    /**
     * @param covers what this question was chosen to cover (skills, concepts, files)
     * @param flags  integrity signals: pasting, leaving the page, typing faster than a person can
     */
    public record Question(int position, String path, int startLine, int endLine, String language, String code,
                           String concept, String prompt, List<String> covers, String answer, Long millis,
                           Integer score, String feedback, List<String> flags) {
    }
}
