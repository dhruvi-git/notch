package dev.notch.api.ideation.core;

import java.util.List;
import java.util.Map;

/**
 * A decision matrix: options compared on weighted criteria.
 *
 * <p>{@code scores} maps criterion id → option id → score from 1 to 5. A missing score means
 * "not scored yet" and counts as nothing for that option on that criterion.
 */
public record Comparison(String question, List<Option> options, List<Criterion> criteria,
                         Map<String, Map<String, Integer>> scores) {

    public record Option(String id, String name, String note) {
    }

    public record Criterion(String id, String name, int weight) {
    }

    public static final int MIN_OPTIONS = 2, MAX_OPTIONS = 8, MAX_CRITERIA = 12;
    public static final int MIN_SCORE = 1, MAX_SCORE = 5, MIN_WEIGHT = 1, MAX_WEIGHT = 5;

    public Comparison {
        question = question == null ? "" : question.strip();
        options = options == null ? List.of() : List.copyOf(options);
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
        scores = scores == null ? Map.of() : scores;
    }

    public Integer score(String criterionId, String optionId) {
        Map<String, Integer> row = scores.get(criterionId);
        return row == null ? null : row.get(optionId);
    }
}
