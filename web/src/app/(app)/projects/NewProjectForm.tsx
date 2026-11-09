"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ClientApiError, postJson } from "@/lib/client-api";
import type { Project } from "@/lib/types";
import styles from "./projects.module.css";

export function NewProjectForm({ first }: { first: boolean }) {
  const router = useRouter();
  const [name, setName] = useState("");
  const [isPrivate, setPrivate] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) {
      setError("Give the project a name.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const p = await postJson<Project>("/projects", { name: name.trim(), isPrivate });
      router.push(`/p/${p.id}/imports`);
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The project wasn't created. Try again.");
      setBusy(false);
    }
  }

  return (
    <form className={styles.form} onSubmit={submit} noValidate>
      <label htmlFor="project-name" className={styles.formLabel}>
        {first ? "Name your first project" : "New project"}
      </label>
      <div className={styles.formRow}>
        <input
          id="project-name"
          className="field"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Billing service"
          maxLength={80}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? "project-error" : undefined}
        />
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? "Creating…" : "Create project"}
        </button>
      </div>
      <label className={styles.check}>
        <input type="checkbox" checked={isPrivate} onChange={(e) => setPrivate(e.target.checked)} />
        <span>
          Keep private. Nothing from this project is sent to an AI model; search and decisions still work.
        </span>
      </label>
      {error ? (
        <p id="project-error" className={styles.error} role="alert">
          {error}
        </p>
      ) : null}
    </form>
  );
}
