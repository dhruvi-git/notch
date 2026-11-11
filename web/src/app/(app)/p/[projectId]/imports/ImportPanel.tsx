"use client";

import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { call, ClientApiError } from "@/lib/client-api";
import { plural } from "@/lib/format";
import { summarizeImport } from "@/lib/import-summary";
import type { ImportReport } from "@/lib/types";
import styles from "./imports.module.css";

type State =
  | { kind: "idle" }
  | { kind: "uploading"; name: string }
  | { kind: "done"; report: ImportReport }
  | { kind: "error"; message: string };

const ACCEPT = ".json,.zip,.md,.markdown,.txt";
const MAX_BYTES = 5 * 1024 * 1024;
const POLL_MS = 1500;

export function ImportPanel({ projectId, remaining, limit }: { projectId: string; remaining: number; limit: number }) {
  const router = useRouter();
  const input = useRef<HTMLInputElement>(null);
  const [state, setState] = useState<State>({ kind: "idle" });
  const [dragging, setDragging] = useState(false);
  // Only the latest upload may update the panel: an earlier one still polling must not overwrite it.
  const latest = useRef(0);

  async function upload(file: File) {
    if (file.size > MAX_BYTES) {
      setState({ kind: "error", message: "Files can be up to 5 MB. Split larger exports into smaller files." });
      return;
    }
    const mine = ++latest.current;
    const current = () => latest.current === mine;
    setState({ kind: "uploading", name: file.name });
    const body = new FormData();
    body.append("file", file);
    try {
      let report = await call<ImportReport>(`/projects/${projectId}/imports`, { method: "POST", body });
      if (!current()) return;
      setState({ kind: "done", report });
      router.refresh();
      // Memories are found in the background; follow along until it finishes.
      while (report.status === "PROCESSING") {
        await new Promise((r) => setTimeout(r, POLL_MS));
        if (!current()) return;
        report = await call<ImportReport>(`/projects/${projectId}/imports/${report.id}`);
        if (!current()) return;
        setState({ kind: "done", report });
      }
      router.refresh();
    } catch (err) {
      if (!current()) return;
      setState({
        kind: "error",
        message: err instanceof ClientApiError ? err.message : "The upload didn't finish. Check your connection and try again.",
      });
    }
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer.files[0];
    if (file) void upload(file);
  }

  const busy = state.kind === "uploading" || (state.kind === "done" && state.report.status === "PROCESSING");
  const outOfImports = remaining <= 0;

  return (
    <section className={styles.panel} aria-labelledby="import-title">
      <div
        className={styles.drop}
        data-dragging={dragging}
        onDragOver={(e) => {
          e.preventDefault();
          if (!busy && !outOfImports) setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={busy || outOfImports ? (e) => e.preventDefault() : onDrop}
      >
        <h2 id="import-title" className={styles.dropTitle}>
          Drop a chat export or a notes file
        </h2>
        <p className={styles.dropHelp}>
          ChatGPT or Claude export (the zip, or <code>conversations.json</code>), a Markdown note, or a pasted
          transcript as <code>.txt</code>. Up to 5 MB.
        </p>
        <div className={styles.dropActions}>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => input.current?.click()}
            disabled={busy || outOfImports}
          >
            {busy ? "Importing…" : "Choose a file"}
          </button>
          <span className={styles.allowance}>
            {outOfImports ? `You've used today's ${limit} imports.` : `${plural(remaining, "import")} left today`}
          </span>
        </div>
        <input
          ref={input}
          type="file"
          accept={ACCEPT}
          className="sr-only"
          tabIndex={-1}
          onChange={(e) => {
            const file = e.target.files?.[0];
            e.target.value = "";
            if (file) void upload(file);
          }}
        />
      </div>

      <div aria-live="polite">
        {state.kind === "uploading" ? (
          <p className={styles.progress}>Reading {state.name}…</p>
        ) : null}
        {state.kind === "error" ? (
          <p className={styles.errorMsg} role="alert">
            {state.message}
          </p>
        ) : null}
        {state.kind === "done" ? <Result report={state.report} /> : null}
      </div>
    </section>
  );
}

function Result({ report }: { report: ImportReport }) {
  const { headline, notes } = summarizeImport(report);
  return (
    <div className={styles.result} data-status={report.status}>
      <p className={styles.resultHeadline}>{headline}</p>
      <ul className={styles.resultNotes}>
        {notes.map((n) => (
          <li key={n}>{n}</li>
        ))}
      </ul>
      <details className={styles.details}>
        <summary>How the repeat check went</summary>
        <p>
          Each passage was checked against everything already in this project. A fast filter flagged{" "}
          {plural(report.duplicateChunks + report.bloomFalsePositives, "passage")} as possibly seen; an exact check
          confirmed {plural(report.duplicateChunks, "repeat")} and cleared{" "}
          {plural(report.bloomFalsePositives, "false alarm")}. {plural(report.chunksAdded, "new passage")} were stored.
        </p>
      </details>
    </div>
  );
}
