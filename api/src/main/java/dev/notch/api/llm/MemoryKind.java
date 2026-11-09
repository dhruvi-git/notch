package dev.notch.api.llm;

/** What a memory records. */
public enum MemoryKind {
    /** A choice the project made, with its rationale and what it ruled out. */
    DECISION,
    /** Something still to do. */
    TASK,
    /** An error and how it was fixed. */
    FIX,
    /** Background worth remembering (stack, constraints, conventions). */
    CONTEXT
}
