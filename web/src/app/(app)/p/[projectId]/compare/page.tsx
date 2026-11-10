import type { Metadata } from "next";
import Link from "next/link";
import { api } from "@/lib/server-api";
import { relativeTime } from "@/lib/format";
import type { ComparisonSummary } from "@/lib/types";
import { NewComparison } from "./NewComparison";
import styles from "./compare.module.css";

export const metadata: Metadata = { title: "Compare" };

export default async function ComparePage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  const list = await api<ComparisonSummary[]>(`/api/projects/${projectId}/comparisons`);
  return (
    <div className={styles.page}>
      <section className={`${styles.stage} on-brand`} aria-labelledby="compare-title">
        <h1 id="compare-title" className={styles.stageTitle}>
          Weigh your options
        </h1>
        <p className={styles.stageLede}>
          Score each option against what matters, see how sure the result is, then make it a decision. The options you
          don&rsquo;t pick are recorded as ruled out, so Notch can stop them coming back.
        </p>
        <NewComparison projectId={projectId} />
      </section>

      <section className={styles.listSection} aria-labelledby="list-title">
        <h2 id="list-title">Comparisons</h2>
        {list.length === 0 ? (
          <p className="muted">None yet. Start with a choice you&rsquo;re weighing right now.</p>
        ) : (
          <ul className={styles.list}>
            {list.map((c) => (
              <li key={c.id}>
                <Link href={`/p/${projectId}/compare/${c.id}`} className={styles.card}>
                  <span className={styles.cardQuestion}>{c.question}</span>
                  <span className={styles.cardOptions}>
                    {c.options.map((o) => (
                      <span key={o} className={styles.option} data-chosen={o === c.chosen || (!c.chosen && o === c.leader)}>
                        {o}
                      </span>
                    ))}
                  </span>
                  <span className={styles.cardMeta}>
                    {c.status === "DECIDED" ? (
                      <span className="chip chip-ok">Decided: {c.chosen}</span>
                    ) : c.leader ? (
                      <span className="chip">Leading: {c.leader}</span>
                    ) : (
                      <span className="chip">Not scored yet</span>
                    )}
                    <span>Updated {relativeTime(c.updatedAt).toLowerCase()}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
