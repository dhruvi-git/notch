import { plural } from "./format";
import type { ImportReport } from "./types";

/**
 * Turns an import report into plain sentences: what was added, then what Notch did on the
 * person's behalf (hidden secrets, skipped repeats, memories found).
 */
export function summarizeImport(r: ImportReport): { headline: string; notes: string[] } {
  if (r.status === "FAILED") {
    return { headline: "This import couldn't be processed", notes: [r.error ?? "Try importing the file again."] };
  }
  const conversations = r.conversationsAdded + r.conversationsUpdated;
  const headline =
    r.messagesAdded === 0
      ? "Nothing new: every message was already here"
      : `${plural(r.messagesAdded, "new message")} from ${plural(conversations, "conversation")}`;

  const notes: string[] = [];
  if (r.secretsRedacted > 0) {
    notes.push(`${plural(r.secretsRedacted, "secret")} hidden before saving`);
  }
  if (r.messagesSkipped > 0) {
    notes.push(`${plural(r.messagesSkipped, "message")} already imported, skipped`);
  }
  if (r.duplicateChunks > 0) {
    notes.push(`${plural(r.duplicateChunks, "repeated passage")} skipped`);
  }
  if (r.status === "PROCESSING") {
    notes.push("Finding decisions, tasks and fixes…");
  } else if (r.messagesAdded > 0) {
    notes.push(
      r.memoriesExtracted === 0
        ? "No decisions, tasks or fixes found in the new messages"
        : `${plural(r.memoriesExtracted, "decision, task or fix", "decisions, tasks and fixes")} found`,
    );
  }
  return { headline, notes };
}

/** "A. B. Finding…" — one sentence per note, without doubling punctuation after an ellipsis. */
export function notesAsSentences(notes: string[]): string {
  return notes.map((n) => (/[.…]$/.test(n) ? n : n + ".")).join(" ");
}
