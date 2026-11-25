package dev.notch.api.llm;

import java.util.List;

/**
 * What a model needs to write a recruiter's first message to a candidate.
 *
 * @param strengths the candidate's evidence on the role's skills, one line each ("SQL 82, verified")
 */
public record OutreachPrompt(String recruiterName, String driveTitle, String roleName, String candidateHandle,
                             String candidateHeadline, List<String> strengths) {
}
