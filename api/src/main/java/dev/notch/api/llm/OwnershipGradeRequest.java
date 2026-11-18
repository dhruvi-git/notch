package dev.notch.api.llm;

import java.util.List;

/** Asking a model to grade one answer in an Ownership Check against the code it is about. */
public record OwnershipGradeRequest(String path, String code, String question, List<String> rubric, String answer) {
}
