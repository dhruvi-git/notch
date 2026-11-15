package dev.notch.api.review;

import java.util.List;

/**
 * A code review: an overall assessment, a breakdown by area, strengths, issues and
 * recommendations. The same shape comes from a zip reviewed on the server and from Claude Code
 * running /notch review, so the web app shows both the same way.
 *
 * @param summary         the overall assessment, a short paragraph
 * @param areas           the breakdown: each part of the codebase with a rating and a sentence or two
 * @param strengths       what the codebase does well
 * @param issues          problems found, most severe first
 * @param recommendations what to do next, most urgent first
 * @param coverage        what was reviewed and by whom
 */
public record ReviewReport(String summary, List<Area> areas, List<String> strengths, List<Issue> issues,
                           List<Recommendation> recommendations, Coverage coverage) {

    /** {@code rating} is "good", "fair" or "weak". */
    public record Area(String name, String rating, String assessment) {
    }

    /**
     * @param severity       "high", "medium" or "low"
     * @param category       "bug", "security", "performance", "maintainability", "testing", "decision" or "other"
     * @param file           path from the repository root, or null for the codebase as a whole
     * @param line           1-based line in {@code file}, or null
     * @param detail         what's wrong and why it matters
     * @param recommendation how to fix it
     * @param source         "model" (written by Claude) or "rules" (found by Notch's automated checks)
     */
    public record Issue(String severity, String category, String title, String file, Integer line, String detail,
                        String recommendation, String source) {
    }

    /** {@code priority} is "now", "soon" or "later". */
    public record Recommendation(String priority, String title, String detail) {
    }

    /**
     * @param reviewer      who wrote the review: a model name, "Claude Code", or "automated checks"
     * @param filesTotal    code files in the codebase
     * @param filesReviewed code files the reviewer read
     * @param note          anything the reader should know, such as why the code wasn't sent to a model
     */
    public record Coverage(String reviewer, int filesTotal, int filesReviewed, int linesReviewed, String note) {
    }

    public long highIssues() {
        return issues.stream().filter(i -> "high".equals(i.severity())).count();
    }
}
