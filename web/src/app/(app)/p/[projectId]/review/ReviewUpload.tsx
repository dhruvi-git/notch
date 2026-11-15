"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { call, ClientApiError } from "@/lib/client-api";
import { fromDrop, fromInput, zipFolder, type PickedFile } from "@/lib/folder-zip";
import type { ReviewSummary } from "@/lib/types";
import styles from "./review.module.css";

const MAX_BYTES = 100 * 1024 * 1024;

/**
 * Send a codebase for review: a folder (zipped here, without node_modules, .git or build output)
 * or a zip. The review runs in the background and the page follows it.
 */
export function ReviewUpload({ projectId, compact = false }: { projectId: string; compact?: boolean }) {
  const router = useRouter();
  const zipInput = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const busy = status !== null;

  useEffect(() => {
    folderInput.current?.setAttribute("webkitdirectory", "");
  }, []);

  async function uploadFolder(picked: PickedFile[]) {
    setError(null);
    if (picked.length === 0) return setError("That folder has no source files Notch can read.");
    setStatus(`Packing ${picked.length} files…`);
    try {
      await upload(await zipFolder(picked));
    } catch {
      setStatus(null);
      setError("The folder couldn't be read. Try again, or upload a zip of it.");
    }
  }

  async function upload(file: File | undefined) {
    if (!file) return;
    setError(null);
    if (!file.name.toLowerCase().endsWith(".zip")) return setError("Choose your project folder, or a .zip of it.");
    if (file.size > MAX_BYTES) {
      return setError("That zip is over 100 MB. Choose the folder instead: Notch leaves out node_modules and build output.");
    }
    setStatus("Uploading…");
    const body = new FormData();
    body.append("file", file);
    try {
      const started = await call<ReviewSummary>(`/projects/${projectId}/reviews/zip`, { method: "POST", body });
      router.push(`/p/${projectId}/review?r=${started.id}`);
      router.refresh();
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The upload didn't finish. Try again.");
    } finally {
      setStatus(null);
    }
  }

  const pickers = (
    <>
      <input ref={zipInput} type="file" accept=".zip,application/zip" className="sr-only" tabIndex={-1}
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = "";
          void upload(file);
        }} />
      <input ref={folderInput} type="file" multiple className="sr-only" tabIndex={-1}
        onChange={(e) => {
          const picked = e.target.files ? fromInput(e.target.files) : [];
          e.target.value = "";
          void uploadFolder(picked);
        }} />
    </>
  );

  if (compact) {
    return (
      <>
        {pickers}
        <button type="button" className="btn btn-yellow" onClick={() => folderInput.current?.click()} disabled={busy}>
          {status ?? "Review a folder"}
        </button>
        <button type="button" className="btn btn-quiet" onClick={() => zipInput.current?.click()} disabled={busy}>
          or a zip
        </button>
        {error ? <p className={styles.compactError} role="alert">{error}</p> : null}
      </>
    );
  }

  return (
    <section
      className={styles.drop}
      data-dragging={dragging}
      aria-labelledby="review-zip-title"
      onDragOver={(e) => {
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={async (e) => {
        e.preventDefault();
        setDragging(false);
        const file = e.dataTransfer.files[0];
        const folder = await fromDrop(e.dataTransfer.items).catch(() => null);
        if (folder) void uploadFolder(folder);
        else void upload(file);
      }}
    >
      {pickers}
      <h2 id="review-zip-title" className={styles.dropTitle}>Drop your project folder</h2>
      <p>
        You get an overall assessment, a breakdown by area, the issues with file and line, and what to do next. No need to
        zip it: <code>node_modules</code>, <code>.git</code>, build output and local <code>.env</code> files are left out.
        A zip works too, including GitHub&rsquo;s &ldquo;Download ZIP&rdquo;.
      </p>
      <div className={styles.stageActions}>
        <button type="button" className="btn btn-primary" onClick={() => folderInput.current?.click()} disabled={busy}>
          {status ?? "Choose a folder"}
        </button>
        <button type="button" className="btn btn-quiet" onClick={() => zipInput.current?.click()} disabled={busy}>
          Choose a zip
        </button>
      </div>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <p className={styles.dropNote}>
        Secrets are removed before Claude reads the code, and the code is discarded once the review is written. Each upload
        counts as one of today&rsquo;s uploads.
      </p>
    </section>
  );
}
