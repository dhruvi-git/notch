import { ConnectRepo } from "@/components/ConnectRepo";
import type { Metadata } from "next";
import { api } from "@/lib/server-api";
import { relativeTime, SOURCE_LABEL } from "@/lib/format";
import { notesAsSentences, summarizeImport } from "@/lib/import-summary";
import type { ImportReport, Usage } from "@/lib/types";
import { ImportPanel } from "./ImportPanel";
import styles from "./imports.module.css";

export const metadata: Metadata = { title: "Upload" };

export default async function Imports({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  const [imports, usage] = await Promise.all([
    api<ImportReport[]>(`/api/projects/${projectId}/imports`),
    api<Usage>("/api/usage"),
  ]);
  return (
    <div className={styles.page}>
      <h1>Upload</h1>
      <p className={styles.intro}>
        Start with your code: drop the project folder and Notch reviews it and maps how it works. Add chats whenever you
        like, or connect the repository and Notch captures your Claude Code sessions and commits by itself. Secrets are
        hidden before anything is saved.
      </p>

      <ImportPanel projectId={projectId} remaining={usage.remaining.import} limit={usage.limits.import} />

      <ConnectRepo projectId={projectId} compact />

      <section className={styles.history} aria-labelledby="history-title">
        <h2 id="history-title">Chats and notes added</h2>
        {imports.length === 0 ? (
          <p className="muted">No chats or notes yet. They&rsquo;re optional; reviews of your code are under Review.</p>
        ) : (
          <ol className={styles.list}>
            {imports.map((r) => {
              const { headline, notes } = summarizeImport(r);
              return (
                <li key={r.id} className={styles.item}>
                  <div className={styles.itemTop}>
                    <span className={styles.file}>{r.filename ?? "Pasted text"}</span>
                    <span className={styles.when}>
                      <time dateTime={r.createdAt}>{relativeTime(r.createdAt)}</time>
                    </span>
                  </div>
                  <p className={styles.headline}>
                    <span className={r.status === "READY" ? "chip chip-ok" : r.status === "FAILED" ? "chip chip-danger" : "chip"}>
                      {r.status === "READY" ? "Done" : r.status === "PROCESSING" ? "Processing" : "Failed"}
                    </span>
                    {headline}
                    <span className={styles.source}>{SOURCE_LABEL[r.format] ?? r.format}</span>
                  </p>
                  {notes.length > 0 ? <p className={styles.notes}>{notesAsSentences(notes)}</p> : null}
                </li>
              );
            })}
          </ol>
        )}
      </section>
    </div>
  );
}
