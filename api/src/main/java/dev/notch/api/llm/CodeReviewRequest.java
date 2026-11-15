package dev.notch.api.llm;

import java.util.List;

/**
 * Code for a model to review.
 *
 * @param label     the codebase's name
 * @param overview  facts from the code map: size, languages, modules, entry points
 * @param decisions the project's decisions in force, so code that goes against them is caught
 * @param files     the chosen files, secrets redacted, each line numbered as "12| code"
 */
public record CodeReviewRequest(String label, String overview, List<String> decisions, List<File> files) {

    public record File(String path, String numbered, boolean truncated) {
    }
}
