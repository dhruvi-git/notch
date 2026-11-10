import type { Metadata } from "next";
import Link from "next/link";
import { api } from "@/lib/server-api";
import { plural, relativeTime, shortDate } from "@/lib/format";
import type { ResumeView } from "@/lib/types";
import { TaskList } from "./TaskList";
import styles from "./resume.module.css";

export const metadata: Metadata = { title: "Where you stopped" };

/** "You asked: …" → { label: "You asked", text: "…" } */
function splitLine(line: string): { label: string; text: string } {
  const i = line.indexOf(": ");
  return i > 0 && i < 30 ? { label: line.slice(0, i), text: line.slice(i + 2) } : { label: "", text: line };
}

export default async function ResumePage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  const r = await api<ResumeView>(`/api/projects/${projectId}/resume`);
  const base = `/p/${projectId}`;
  const quotes = r.whereYouStopped.map(splitLine).filter((l) => l.label !== "Last session");

  if (!r.lastSession) {
    return (
      <div className={styles.page}>
        <h1>Where you stopped</h1>
        <p className={styles.empty}>
          Nothing here yet. <Link href={`${base}/imports`}>Import a conversation</Link> and Notch will pick up the thread
          from its last messages.
        </p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <h1>Where you stopped</h1>
      <section className={`${styles.session} on-brand`} aria-label="Last session">
        <p className={styles.sessionMeta}>
          {relativeTime(r.lastSession.lastActivity)}, in &ldquo;{r.lastSession.title}&rdquo;
          <span className="muted">, {plural(r.lastSession.messages, "message")}</span>
        </p>
        {quotes.map((q) => (
          <figure key={q.label + q.text} className={styles.quote}>
            <figcaption>{q.label}</figcaption>
            <blockquote>{q.text}</blockquote>
          </figure>
        ))}
      </section>

      {r.driftAlerts > 0 ? (
        <p className={styles.drift}>
          <Link href={`${base}/drift`}>{plural(r.driftAlerts, "drift alert")} need a look</Link> before you carry on.
        </p>
      ) : null}

      <section className={styles.section} aria-labelledby="next-up">
        <h2 id="next-up">Next up</h2>
        {r.openTasks.length === 0 ? (
          <p className="muted">No open tasks.</p>
        ) : (
          <TaskList tasks={r.openTasks} />
        )}
      </section>

      <section className={styles.section} aria-labelledby="in-force">
        <div className={styles.sectionHead}>
          <h2 id="in-force">Decisions in force</h2>
          <Link href={`${base}/ledger`} className={styles.more}>
            All decisions
          </Link>
        </div>
        {r.decisions.length === 0 ? (
          <p className="muted">No decisions recorded yet.</p>
        ) : (
          <ul className={styles.compact}>
            {[...r.decisions].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 6).map((d) => (
              <li key={d.id}>
                <span>{d.title}</span>
                <span className={styles.date}>{shortDate(d.createdAt)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {r.fixes.length > 0 ? (
        <section className={styles.section} aria-labelledby="fixes">
          <h2 id="fixes">Recent fixes</h2>
          <ul className={styles.compact}>
            {r.fixes.slice(0, 4).map((f) => (
              <li key={f.id}>
                <code>{f.title.replace(/^Fix:\s*/, "")}</code>
                <span className={styles.date}>{shortDate(f.createdAt)}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
