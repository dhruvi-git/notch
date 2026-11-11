"use client";

import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { call, ClientApiError } from "@/lib/client-api";
import type { CodeUpload } from "@/lib/types";
import styles from "./explain.module.css";

const MAX_BYTES = 25 * 1024 * 1024;

/** Upload a zipped codebase; the server maps it and keeps only the map. */
export function UploadCode({ projectId, compact = false }: { projectId: string; compact?: boolean }) {
  const router = useRouter();
  const input = useRef<HTMLInputElement>(null);
  const [state, setState] = useState<"idle" | "uploading" | "done">("idle");
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);

  async function upload(file: File | undefined) {
    if (!file) return;
    setError(null);
    if (!file.name.toLowerCase().endsWith(".zip")) return setError("Choose a .zip of your code.");
    if (file.size > MAX_BYTES) return setError("That zip is over 25 MB. Leave out node_modules and build folders, or use notch map.");
    setState("uploading");
    const body = new FormData();
    body.append("file", file);
    try {
      await call<CodeUpload>(`/projects/${projectId}/code-maps/zip`, { method: "POST", body });
      setState("done");
      router.refresh();
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The upload didn't finish. Try again.");
      setState("idle");
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
        <button type="button" className="btn btn-quiet" onClick={() => input.current?.click()} disabled={state === "uploading"}>
          {state === "uploading" ? "Mapping…" : "Upload a newer zip"}
        </button>
        {error ? <p className={styles.compactError} role="alert">{error}</p> : null}
      </>
    );
  }

  return (
    <section
      className={styles.drop}
      data-dragging={dragging}
      aria-labelledby="zip-title"
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
      <h2 id="zip-title" className={styles.dropTitle}>Drop a zip of your code</h2>
      <p>
        GitHub&rsquo;s &ldquo;Download ZIP&rdquo; works as is. Up to 25 MB; <code>node_modules</code> and build folders are
        skipped. The code is mapped and then discarded.
      </p>
      <div>
        <button type="button" className="btn btn-primary" onClick={() => input.current?.click()} disabled={state === "uploading"}>
          {state === "uploading" ? "Mapping your code…" : "Choose a zip"}
        </button>
      </div>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <p className={styles.dropNote}>Each upload counts as one of today&rsquo;s imports.</p>
    </section>
  );
}
