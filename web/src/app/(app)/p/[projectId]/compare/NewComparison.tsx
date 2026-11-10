"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ClientApiError, postJson } from "@/lib/client-api";
import type { ComparisonView } from "@/lib/types";
import styles from "./compare.module.css";

export function NewComparison({ projectId }: { projectId: string }) {
  const router = useRouter();
  const [question, setQuestion] = useState("");
  const [options, setOptions] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const names = options.split(",").map((s) => s.trim()).filter(Boolean);
    if (!question.trim()) return setError("Write the question you're deciding.");
    if (names.length < 2) return setError("List at least two options, separated by commas.");
    setBusy(true);
    setError(null);
    try {
      const c = await postJson<ComparisonView>(`/projects/${projectId}/comparisons`, { question: question.trim(), options: names });
      router.push(`/p/${projectId}/compare/${c.id}`);
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The comparison wasn't created. Try again.");
      setBusy(false);
    }
  }

  return (
    <form className={styles.newForm} onSubmit={submit} noValidate>
      <label className={styles.newLabel}>
        <span>What are you deciding?</span>
        <input className={styles.newInput} value={question} onChange={(e) => setQuestion(e.target.value)}
          placeholder="Where should raw analytics events live?" maxLength={200} />
      </label>
      <label className={styles.newLabel}>
        <span>Options, separated by commas</span>
        <input className={styles.newInput} value={options} onChange={(e) => setOptions(e.target.value)}
          placeholder="Postgres, Redis Streams, Kafka" />
      </label>
      <div className={styles.newActions}>
        <button type="submit" className="btn btn-yellow" disabled={busy}>
          {busy ? "Starting…" : "Start comparing"}
        </button>
        {error ? <p className={styles.newError} role="alert">{error}</p> : null}
      </div>
    </form>
  );
}
