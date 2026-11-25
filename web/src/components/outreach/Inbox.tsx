"use client";

import { useEffect, useState, type CSSProperties } from "react";
import { call, ClientApiError, postJson } from "@/lib/client-api";
import type { OutreachMessage } from "@/lib/portfolio-types";
import styles from "./outreach.module.css";

/**
 * Recruiters who reached out to the candidate, newest first, each with a way to answer. Shows
 * nothing until the first message arrives.
 */
export function Inbox() {
  const [messages, setMessages] = useState<OutreachMessage[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    call<OutreachMessage[]>("/outreach/inbox")
      .then(setMessages)
      .catch(() => setMessages([]));
  }, []);

  async function answer(id: string, interested: boolean) {
    setBusy(id);
    setError(null);
    try {
      const m = await postJson<OutreachMessage>(`/outreach/${id}/respond`, { interested });
      setMessages((cur) => (cur ?? []).map((x) => (x.id === id ? m : x)));
    } catch (err) {
      setError(err instanceof ClientApiError && err.message ? err.message : "Your answer wasn't saved. Try again.");
    } finally {
      setBusy(null);
    }
  }

  if (!messages || messages.length === 0) return null;
  const waiting = messages.filter((m) => m.status === "SENT").length;

  return (
    <section className={`${styles.inbox} rise`} aria-labelledby="inbox-title">
      <div className={styles.inboxHead}>
        <h2 id="inbox-title" className={styles.inboxTitle}>
          Recruiters interested in you
        </h2>
        {waiting > 0 ? <span className={styles.badge}>{waiting} new</span> : null}
      </div>
      <p className={styles.inboxLede}>They found you through your work on Notch. Answer here and they&rsquo;ll see it straight away.</p>
      <ul className={styles.messages}>
        {messages.map((m, i) => (
          <li key={m.id} className={`${styles.message} rise`} style={{ "--i": i } as CSSProperties}>
            <div className={styles.messageMeta}>
              <strong>@{m.recruiter}</strong>
              <span>
                {m.role && m.role.toLowerCase() !== m.drive.toLowerCase() ? `${m.role} · ` : ""}
                {m.drive}
              </span>
              <time dateTime={m.sentAt}>{new Date(m.sentAt).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}</time>
            </div>
            <p className={styles.messageSubject}>{m.subject}</p>
            <p className={styles.messageBody}>{m.body}</p>
            {m.status === "SENT" ? (
              <div className={styles.actions}>
                <button type="button" className="btn btn-primary" onClick={() => answer(m.id, true)} disabled={busy === m.id}>
                  I&rsquo;m interested
                </button>
                <button type="button" className="btn btn-quiet" onClick={() => answer(m.id, false)} disabled={busy === m.id}>
                  Not now
                </button>
              </div>
            ) : (
              <p className={m.status === "INTERESTED" ? styles.answeredYes : styles.answeredNo}>
                {m.status === "INTERESTED" ? "You said you're interested. They can see that now." : "You said not now."}
              </p>
            )}
          </li>
        ))}
      </ul>
      {error ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}
