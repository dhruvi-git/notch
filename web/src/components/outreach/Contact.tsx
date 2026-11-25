"use client";

import { useState } from "react";
import { call, ClientApiError, postJson } from "@/lib/client-api";
import type { OutreachDraft, OutreachSent, OutreachStatus } from "@/lib/portfolio-types";
import styles from "./outreach.module.css";

function message(err: unknown, fallback: string): string {
  return err instanceof ClientApiError && err.message ? err.message : fallback;
}

/** Where a sent message stands, as a small chip: the candidate's answer first, then how it was delivered. */
export function ContactStatus({ status }: { status: Pick<OutreachStatus, "status" | "delivery"> }) {
  if (status.status === "INTERESTED") return <span className={`${styles.chip} ${styles.chipYes}`}>Interested</span>;
  if (status.status === "DECLINED") return <span className={`${styles.chip} ${styles.chipNo}`}>Not now</span>;
  return <span className={styles.chip}>{status.delivery === "EMAILED" ? "Contacted · emailed" : "Contacted"}</span>;
}

/**
 * Contact a candidate from a drive: Notch writes the message from their evidence, the recruiter
 * can edit it, and Send delivers it to the candidate's Notch inbox (and by email when set up).
 */
export function ContactButton({
  driveId,
  roleId,
  candidateId,
  handle,
  onSent,
}: {
  driveId: string;
  roleId: string | null;
  candidateId: string;
  handle: string;
  onSent: (s: OutreachStatus) => void;
}) {
  const [open, setOpen] = useState(false);
  const [drafting, setDrafting] = useState(false);
  const [sending, setSending] = useState(false);
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [written, setWritten] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function start() {
    setOpen(true);
    setDrafting(true);
    setError(null);
    try {
      const d = await postJson<OutreachDraft>(`/drives/${driveId}/outreach/draft`, { candidateId, roleId });
      setSubject(d.subject);
      setBody(d.body);
      setWritten(d.written);
    } catch (err) {
      setError(message(err, "The message couldn't be written. Try again."));
    } finally {
      setDrafting(false);
    }
  }

  async function send() {
    setSending(true);
    setError(null);
    try {
      const s = await postJson<OutreachSent>(`/drives/${driveId}/outreach`, { candidateId, roleId, subject, body });
      setOpen(false);
      onSent({
        id: s.id,
        candidateId,
        handle,
        role: null,
        status: "SENT",
        delivery: s.delivery,
        sentAt: new Date().toISOString(),
        respondedAt: null,
      });
    } catch (err) {
      setError(message(err, "The message wasn't sent. Try again."));
      setSending(false);
    }
  }

  if (!open) {
    return (
      <button type="button" className={`btn btn-quiet ${styles.contactBtn}`} onClick={start}>
        Contact
      </button>
    );
  }

  return (
    <div className={`${styles.composer} rise`} role="region" aria-label={`Message to @${handle}`}>
      {drafting ? (
        <p className={styles.drafting} aria-live="polite">
          <span className={styles.dot} aria-hidden="true" />
          Writing a message from @{handle}&rsquo;s work…
        </p>
      ) : (
        <>
          <label className={styles.label} htmlFor={`subject-${candidateId}`}>
            Subject
          </label>
          <input
            id={`subject-${candidateId}`}
            className="field"
            value={subject}
            maxLength={200}
            onChange={(e) => setSubject(e.target.value)}
          />
          <label className={styles.label} htmlFor={`body-${candidateId}`}>
            Message {written ? <span className={styles.ai}>written from @{handle}&rsquo;s evidence · edit anything</span> : null}
          </label>
          <textarea
            id={`body-${candidateId}`}
            className={`field ${styles.body}`}
            value={body}
            maxLength={5000}
            rows={9}
            onChange={(e) => setBody(e.target.value)}
          />
          <div className={styles.actions}>
            <button type="button" className="btn btn-primary" onClick={send} disabled={sending || !subject.trim() || !body.trim()}>
              {sending ? "Sending…" : "Send"}
            </button>
            <button type="button" className="btn btn-quiet" onClick={() => setOpen(false)} disabled={sending}>
              Cancel
            </button>
          </div>
        </>
      )}
      {error ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** The drive's sent messages by candidate id, for the shortlist chips. */
export async function loadContacts(driveId: string): Promise<Record<string, OutreachStatus>> {
  const list = await call<OutreachStatus[]>(`/drives/${driveId}/outreach`);
  return Object.fromEntries(list.map((s) => [s.candidateId, s]));
}
