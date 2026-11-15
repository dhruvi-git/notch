"use client";

import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { call, ClientApiError } from "@/lib/client-api";
import type { ReviewSummary } from "@/lib/types";
import styles from "./review.module.css";

const MAX_BYTES = 25 * 1024 * 1024;

/** Upload a zipped codebase for review; the review runs in the background and the page follows it. */
export function ReviewUpload({ projectId, compact = false }: { projectId: string; compact?: boolean }) {
  const router = useRouter();
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);

  async function upload(file: File | undefined) {
    if (!file) return;
    setError(null);
    if (!file.name.toLowerCase().endsWith(".zip")) return setError("Choose a .zip of your code.");
    if (file.size > MAX_BYTES) return setError("That zip is over 25 MB. Leave out node_modules and build folders.");
    setBusy(true);
    const body = new FormData();
    body.append("file", file);
    try {
      const started = await call<ReviewSummary>(`/projects/${projectId}/reviews/zip`, { method: "POST", body });
      router.push(`/p/${projectId}/review?r=${started.id}`);
      router.refresh();
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The upload didn't finish. Try again.");
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }

  const picker = (
    <input ref={input} type="file" accept=".zip,application/zip" className="sr-only" tabIndex={-1}
      onChange={(e) => upload(e.target.files?.[0])} />
  );

  if (compact) {
    return (
      <>
        {picker}
        <button type="button" className="btn btn-yellow" onClick={() => input.current?.click()} disabled={busy}>
          {busy ? "Uploading…" : "Review a new zip"}
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
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        void upload(e.dataTransfer.files[0]);
      }}
    >
      {picker}
      <h2 id="review-zip-title" className={styles.dropTitle}>Drop a zip of your code</h2>
      <p>
        You get an overall assessment, a breakdown by area, the issues with file and line, and what to do next. GitHub&rsquo;s
        &ldquo;Download ZIP&rdquo; works as is; <code>node_modules</code> and build folders are skipped.
      </p>
      <div>
        <button type="button" className="btn btn-primary" onClick={() => input.current?.click()} disabled={busy}>
          {busy ? "Uploading…" : "Choose a zip"}
        </button>
      </div>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <p className={styles.dropNote}>
        Up to 25 MB. Secrets are removed before Claude reads the code, and the code is discarded once the review is written.
        Each upload counts as one of today&rsquo;s imports.
      </p>
    </section>
  );
}
