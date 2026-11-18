"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ClientApiError, postJson } from "@/lib/client-api";
import type { Drive } from "@/lib/portfolio-types";
import common from "@/components/portfolio/portfolio.module.css";
import styles from "./hire.module.css";

export function NewDriveForm({ first }: { first: boolean }) {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim() || !description.trim()) {
      setError("Add a title and paste the job description.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const d = await postJson<Drive>("/drives", { title: title.trim(), description: description.trim() });
      router.push(`/hire/${d.id}`);
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The drive wasn't created. Try again.");
      setBusy(false);
    }
  }

  return (
    <form className={`${common.card} ${styles.newDrive}`} onSubmit={submit} noValidate>
      <h2 className={common.cardTitle}>{first ? "Start your first drive" : "New drive"}</h2>
      <label className={styles.field}>
        <span>Title</span>
        <input className="field" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} placeholder="Data analyst intern" />
      </label>
      <label className={styles.field}>
        <span>Job description</span>
        <textarea
          className="field"
          rows={8}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          maxLength={20000}
          placeholder={"Must have strong SQL and Python.\nDashboards in Power BI.\nMachine learning is a plus."}
        />
      </label>
      <p className={`${common.muted} ${common.small}`}>
        Sentences with &ldquo;must&rdquo; or &ldquo;required&rdquo; become must-haves; &ldquo;a plus&rdquo; or &ldquo;nice to have&rdquo; become
        nice-to-haves. You can change everything next.
      </p>
      <div>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? "Reading the description…" : "Create drive"}
        </button>
      </div>
      {error ? (
        <p className={common.error} role="alert">
          {error}
        </p>
      ) : null}
    </form>
  );
}
