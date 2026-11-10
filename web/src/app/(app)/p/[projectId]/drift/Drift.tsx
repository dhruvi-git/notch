"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { call, ClientApiError, postJson } from "@/lib/client-api";
import { plural, relativeTime, shortDate } from "@/lib/format";
import type { DriftAlert, Memory } from "@/lib/types";
import styles from "./drift.module.css";

type Props = { projectId: string; initial: DriftAlert[]; titles: Record<string, string> };

export function Drift({ projectId, initial, titles }: Props) {
  const router = useRouter();
  const [alerts, setAlerts] = useState(initial);
  const [resolved, setResolved] = useState<string | null>(null);

  function done(key: string, message: string) {
    setAlerts((a) => a.filter((x) => x.key !== key));
    setResolved(message);
    router.refresh();  // updates the badge in the rail
  }

  return (
    <div className={styles.page}>
      <h1>Drift</h1>
      <p className={styles.intro}>
        Places where newer work goes against a decision in force. Settle each one by keeping the decision that should
        stand, or dismiss it if the difference is intended.
      </p>
      <p className={styles.status} role="status">
        {resolved ? `${resolved} ` : null}
        {alerts.length === 0
          ? "Nothing to review: recent work follows every decision in force."
          : `${plural(alerts.length, "item")} to review.`}
      </p>
      {alerts.length > 0 ? (
        <ul className={styles.list}>
          {alerts.map((a) =>
            a.type === "DECISION_CONFLICT" ? (
              <Conflict key={a.key} alert={a} newerTitle={a.conflictingDecisionId ? titles[a.conflictingDecisionId] : undefined}
                projectId={projectId} onDone={done} />
            ) : (
              <Usage key={a.key} alert={a} projectId={projectId} onDone={done} />
            ),
          )}
        </ul>
      ) : (
        <p className={styles.empty}>
          Notch checks every import against the <Link href={`/p/${projectId}/ledger`}>decisions in the Ledger</Link>.
        </p>
      )}
    </div>
  );
}

function Severity({ alert }: { alert: DriftAlert }) {
  return (
    <span className={alert.severity === "HIGH" ? "chip chip-danger" : "chip chip-warn"}>
      {alert.severity === "HIGH" ? "High" : "Medium"}
    </span>
  );
}

type ActionProps = { projectId: string; onDone: (key: string, message: string) => void };

function useAction(onDone: ActionProps["onDone"], key: string) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function run(work: () => Promise<unknown>, message: string) {
    setBusy(true);
    setError(null);
    try {
      await work();
      onDone(key, message);
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "That didn't save. Try again.");
      setBusy(false);
    }
  }
  return { busy, error, run };
}

function Conflict({ alert, newerTitle, projectId, onDone }: { alert: DriftAlert; newerTitle?: string } & ActionProps) {
  const { busy, error, run } = useAction(onDone, alert.key);
  const newerId = alert.conflictingDecisionId;
  return (
    <li className={styles.item} data-severity={alert.severity}>
      <div className={styles.itemHead}>
        <Severity alert={alert} />
        <h2 className={styles.itemTitle}>Two decisions disagree</h2>
      </div>
      <dl className={styles.pair}>
        <div>
          <dt>Decided {shortDate(alert.decidedAt)}</dt>
          <dd>{alert.decisionTitle}</dd>
        </div>
        <div>
          <dt>Decided later</dt>
          <dd>{newerTitle ?? "A newer decision"}</dd>
        </div>
      </dl>
      <p className={styles.message}>{alert.message}</p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <div className={styles.actions}>
        {newerId ? (
          <>
            <button type="button" className="btn btn-primary" disabled={busy}
              onClick={() => run(() => postJson<Memory>(`/memories/${alert.decisionId}/supersede`, { replacementId: newerId }),
                "Kept the newer decision; the older one is now in its history.")}>
              Keep the newer decision
            </button>
            <button type="button" className="btn btn-quiet" disabled={busy}
              onClick={() => run(() => postJson<Memory>(`/memories/${newerId}/supersede`, { replacementId: alert.decisionId }),
                "Kept the earlier decision.")}>
              Keep the earlier one
            </button>
          </>
        ) : null}
        <Dismiss projectId={projectId} alert={alert} busy={busy} run={run} />
      </div>
    </li>
  );
}

function Usage({ alert, projectId, onDone }: { alert: DriftAlert } & ActionProps) {
  const { busy, error, run } = useAction(onDone, alert.key);
  const ev = alert.evidence;
  return (
    <li className={styles.item} data-severity={alert.severity}>
      <div className={styles.itemHead}>
        <Severity alert={alert} />
        <h2 className={styles.itemTitle}>A chat uses something a decision ruled out</h2>
      </div>
      <dl className={styles.pair}>
        <div>
          <dt>Decided {shortDate(alert.decidedAt)}</dt>
          <dd>{alert.decisionTitle}</dd>
        </div>
        {ev ? (
          <div>
            <dt>
              In &ldquo;{ev.conversationTitle}&rdquo;, {relativeTime(ev.sentAt).toLowerCase()}
            </dt>
            <dd>
              <mark>{ev.sentence}</mark>
            </dd>
          </div>
        ) : null}
      </dl>
      <p className={styles.message}>{alert.message}</p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <div className={styles.actions}>
        <Link href={`/p/${projectId}/ledger`} className="btn btn-quiet">
          Change the decision
        </Link>
        <Dismiss projectId={projectId} alert={alert} busy={busy} run={run} />
      </div>
    </li>
  );
}

function Dismiss({ projectId, alert, busy, run }: {
  projectId: string;
  alert: DriftAlert;
  busy: boolean;
  run: (work: () => Promise<unknown>, message: string) => Promise<void>;
}) {
  return (
    <button type="button" className={styles.dismiss} disabled={busy}
      onClick={() => run(() => call(`/projects/${projectId}/drift/${alert.key}/dismiss`, { method: "POST" }),
        "Dismissed; it won't be shown again.")}>
      Dismiss, this is intended
    </button>
  );
}
