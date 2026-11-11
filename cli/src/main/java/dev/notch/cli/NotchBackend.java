package dev.notch.cli;

import dev.notch.guard.Decision;

import java.util.List;

/** What the CLI and the MCP server need from Notch. An interface so tests can supply a fake. */
public interface NotchBackend {

    List<Decision> decisions() throws NotchException;

    /** Plain-text search results, ready to show an agent. */
    String search(String query) throws NotchException;

    /** Plain-text "where we stopped" summary. */
    String whereWeStopped() throws NotchException;

    /** Records a decision or task; returns its id. */
    String record(String kind, String title, String rationale, List<String> ruledOut) throws NotchException;

    /** Replaces a decision in force with a new one; the old one stays in its history. Returns the new id. */
    String replace(String decisionId, String title, String rationale, List<String> ruledOut) throws NotchException;

    String projectName() throws NotchException;

    final class NotchException extends Exception {
        public NotchException(String message) {
            super(message);
        }

        public NotchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
