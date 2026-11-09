"use client";

import { useState } from "react";
import { ClientApiError, postJson } from "@/lib/client-api";
import type { ResumeView } from "@/lib/types";
import styles from "./resume.module.css";

/** Open tasks in priority order; ticking one marks it done in the Ledger too. */
export function TaskList({ tasks }: { tasks: ResumeView["openTasks"] }) {
  const [done, setDone] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);

  async function toggle(id: string) {
    const next = !done.has(id);
    setDone((d) => {
      const s = new Set(d);
      if (next) s.add(id);
      else s.delete(id);
      return s;
    });
    try {
      await postJson(`/memories/${id}`, { status: next ? "done" : "active" }, "PATCH");
    } catch (err) {
      setDone((d) => {
        const s = new Set(d);
        if (next) s.delete(id);
        else s.add(id);
        return s;
      });
      setError(err instanceof ClientApiError ? err.message : "That change wasn't saved. Try again.");
    }
  }

  return (
    <>
      <ol className={styles.tasks}>
        {tasks.map((t) => (
          <li key={t.id} data-done={done.has(t.id)}>
            <label>
              <input type="checkbox" checked={done.has(t.id)} onChange={() => toggle(t.id)} />
              <span>{t.title}</span>
            </label>
          </li>
        ))}
      </ol>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
    </>
  );
}
