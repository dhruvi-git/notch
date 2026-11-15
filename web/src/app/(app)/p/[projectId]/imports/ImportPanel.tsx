"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { call, ClientApiError } from "@/lib/client-api";
import { fromDrop, fromInput, zipFolder, type PickedFile } from "@/lib/folder-zip";
import { plural } from "@/lib/format";
import { summarizeImport } from "@/lib/import-summary";
import type { ImportReport, ReviewSummary } from "@/lib/types";
import styles from "./imports.module.css";

type State =
  | { kind: "idle" }
  | { kind: "uploading"; name: string }
  | { kind: "zipping"; name: string; files: number }
  | { kind: "reviewing"; name: string }
  | { kind: "done"; report: ImportReport }
  | { kind: "error"; message: string };

type Uploaded = { kind: "code" | "chats"; review: ReviewSummary | null; imported: ImportReport | null };

const ACCEPT = ".zip,.json,.md,.markdown,.txt";
const MAX_BYTES = 100 * 1024 * 1024;
const POLL_MS = 1500;

/**
 * Add anything to the project: a project folder or a zip of code (reviewed and mapped), or chat
 * exports and notes (imported). The server tells code from chats, so nothing here asks which.
 */
export function ImportPanel({ projectId, remaining, limit }: { projectId: string; remaining: number; limit: number }) {
  const router = useRouter();
  const fileInput = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);
  const [state, setState] = useState<State>({ kind: "idle" });
  const [dragging, setDragging] = useState(false);
  // Only the latest upload may update the panel: an earlier one still polling must not overwrite it.
  const latest = useRef(0);

  useEffect(() => {
    // Not in React's types; lets the picker choose a whole folder.
    folderInput.current?.setAttribute("webkitdirectory", "");
  }, []);

  async function uploadFolder(picked: PickedFile[]) {
    if (picked.length === 0) {
      setState({ kind: "error", message: "That folder has no source files Notch can read." });
      return;
    }
    const name = picked[0].path.split("/")[0] || "your folder";
    setState({ kind: "zipping", name, files: picked.length });
    let zip: File;
    try {
      zip = await zipFolder(picked);
    } catch {
      setState({ kind: "error", message: "The folder couldn't be read. Try again, or upload a zip of it." });
      return;
    }
    await upload(zip);
  }

  async function upload(file: File) {
    if (file.size > MAX_BYTES) {
      setState({
        kind: "error",
        message: "Files can be up to 100 MB. For a codebase, choose the folder instead: Notch leaves out node_modules and build output.",
      });
      return;
    }
    const mine = ++latest.current;
    const current = () => latest.current === mine;
    setState({ kind: "uploading", name: file.name });
    const body = new FormData();
    body.append("file", file);
    try {
      const result = await call<Uploaded>(`/projects/${projectId}/uploads`, { method: "POST", body });
      if (!current()) return;
      if (result.kind === "code" && result.review) {
        setState({ kind: "reviewing", name: file.name });
        router.push(`/p/${projectId}/review?r=${result.review.id}`);
        return;
      }
      if (!result.imported) throw new Error("no import in the reply");
      let report = result.imported;
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

  async function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer.files[0];
    const folder = await fromDrop(e.dataTransfer.items).catch(() => null);
    if (folder) return uploadFolder(folder);
    if (file) void upload(file);
  }

  const busy =
    state.kind === "uploading" ||
    state.kind === "zipping" ||
    state.kind === "reviewing" ||
    (state.kind === "done" && state.report.status === "PROCESSING");
  const outOfUploads = remaining <= 0;

  return (
    <section className={styles.panel} aria-labelledby="import-title">
      <div
        className={styles.drop}
        data-dragging={dragging}
        onDragOver={(e) => {
          e.preventDefault();
          if (!busy && !outOfUploads) setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={busy || outOfUploads ? (e) => e.preventDefault() : onDrop}
      >
        <h2 id="import-title" className={styles.dropTitle}>
          Drop your code here
        </h2>
        <p className={styles.dropHelp}>
          A project folder, or a zip of one, gets a full review and a map of how it works. There&rsquo;s no need to zip it:{" "}
          <code>node_modules</code>, <code>.git</code> and build output are left out. Chats are optional. Drop a ChatGPT or
          Claude export, a Markdown note or a transcript to add the history behind the code.
        </p>
        <div className={styles.dropActions}>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => folderInput.current?.click()}
            disabled={busy || outOfUploads}
          >
            {busy ? "Working…" : "Choose a folder"}
          </button>
          <button
            type="button"
            className="btn btn-quiet"
            onClick={() => fileInput.current?.click()}
            disabled={busy || outOfUploads}
          >
            Choose a file
          </button>
          <span className={styles.allowance}>
            {outOfUploads ? `You've used today's ${limit} uploads.` : `${plural(remaining, "upload")} left today`}
          </span>
        </div>
        <input
          ref={fileInput}
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
        <input
          ref={folderInput}
          type="file"
          multiple
          className="sr-only"
          tabIndex={-1}
          onChange={(e) => {
            const picked = e.target.files ? fromInput(e.target.files) : [];
            e.target.value = "";
            void uploadFolder(picked);
          }}
        />
      </div>

      <div aria-live="polite">
        {state.kind === "uploading" ? <p className={styles.progress}>Uploading {state.name}…</p> : null}
        {state.kind === "zipping" ? (
          <p className={styles.progress}>
            Packing {plural(state.files, "file")} from {state.name}…
          </p>
        ) : null}
        {state.kind === "reviewing" ? <p className={styles.progress}>Reviewing {state.name}. Opening Review…</p> : null}
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
