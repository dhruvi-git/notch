import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { TopBar } from "@/components/portfolio/TopBar";
import { api, ApiError } from "@/lib/server-api";
import type { Assignment, Drive } from "@/lib/portfolio-types";
import type { User } from "@/lib/types";
import common from "@/components/portfolio/portfolio.module.css";
import { AssignmentActions } from "./AssignmentActions";
import styles from "../../hire.module.css";

export const metadata: Metadata = { title: "Seat assignment" };

type Params = { driveId: string };

export default async function AssignmentPage({ params }: { params: Promise<Params> }) {
  const { driveId } = await params;
  const id = encodeURIComponent(driveId);
  let drive: Drive;
  try {
    drive = await api<Drive>(`/api/drives/${id}`);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 400)) notFound();
    throw e;
  }
  let result: Assignment | null = null;
  try {
    result = await api<Assignment>(`/api/drives/${id}/assignment`);
  } catch (e) {
    if (!(e instanceof ApiError && e.code === "no_assignment")) throw e;
  }
  const user = await api<User>("/api/me");

  return (
    <div>
      <TopBar user={user} current="hire" />
      <main className={common.container}>
        <p className={`${common.muted} ${common.small}`}>
          <Link href={`/hire/${drive.id}`}>← {drive.title}</Link>
        </p>
        {!result ? (
          <>
            <h1 className={common.title}>No seats assigned yet</h1>
            <p className={common.lede}>Assign the drive&rsquo;s seats to see who goes where.</p>
            <AssignmentActions driveId={drive.id} result={null} />
          </>
        ) : (
          <Result drive={drive} result={result} />
        )}
      </main>
    </div>
  );
}

function Result({ drive, result }: { drive: Drive; result: Assignment }) {
  const all = result.filled === result.totalSeats;
  const pct = (n: number) => (result.totalSeats === 0 ? 0 : (100 * n) / result.totalSeats);
  const gained = result.filled - result.greedyFilled;
  return (
    <>
      <h1 className={common.title}>
        {all ? `All ${result.totalSeats} seats filled` : `${result.filled} of ${result.totalSeats} seats filled`}
      </h1>

      <div className={styles.summary}>
        <div className={styles.compare}>
          <div className={styles.compareBars}>
            <div className={styles.compareRow}>
              <strong>Notch (max-flow)</strong>
              <span className={styles.compareTrack} role="img" aria-label={`${result.filled} of ${result.totalSeats} seats`}>
                <span className={styles.compareFill} style={{ width: `${pct(result.filled)}%` }} />
              </span>
              <span className={styles.compareNum}>
                {result.filled}/{result.totalSeats}
              </span>
            </div>
            <div className={styles.compareRow}>
              <span>Best score first</span>
              <span className={styles.compareTrack} role="img" aria-label={`${result.greedyFilled} of ${result.totalSeats} seats`}>
                <span className={styles.compareFillGreedy} style={{ width: `${pct(result.greedyFilled)}%` }} />
              </span>
              <span className={styles.compareNum}>
                {result.greedyFilled}/{result.totalSeats}
              </span>
            </div>
          </div>
          <p className={styles.compareText}>
            {gained > 0
              ? `Giving each seat to the best free match in turn would have left ${gained} more seat${gained === 1 ? "" : "s"} empty. Notch moved strong all-rounders to roles only they could fill.`
              : "Here, giving each seat to the best free match in turn fills as many seats, so no one needed moving."}
            {!all ? " Some seats stay empty because not enough candidates meet those roles' minimum match." : ""}
          </p>
        </div>
        <div className={`${common.card} ${styles.facts}`}>
          <span className={common.muted}>
            <strong>{result.augmentingPaths}</strong> re-routing{result.augmentingPaths === 1 ? "" : "s"} beyond the best-score-first start
          </span>
          <span className={common.muted}>
            Computed in <strong>{result.millis < 1 ? "under 1" : Math.round(result.millis)} ms</strong>
          </span>
          <span className={common.muted}>Assigned {new Date(result.runAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}</span>
        </div>
      </div>

      <div className={styles.roleGrid}>
        {result.roles.map((r) => (
          <section key={r.roleId} className={common.card} aria-labelledby={`role-${r.roleId}`}>
            <div className={styles.roleHead}>
              <h2 id={`role-${r.roleId}`} className={common.cardTitle}>
                {r.name}
              </h2>
              <span className={styles.roleCount}>
                {r.filled}/{r.seats}
              </span>
            </div>
            {r.eligible !== null ? (
              <p className={`${common.muted} ${common.small}`}>
                {r.eligible} candidate{r.eligible === 1 ? "" : "s"} qualif{r.eligible === 1 ? "ies" : "y"}
              </p>
            ) : null}
            <ul className={styles.people}>
              {r.placements.map((p) => (
                <li key={p.userId} className={styles.person}>
                  <Link href={`/u/${p.handle}`}>@{p.handle}</Link>
                  <span className={styles.personScore}>{Math.round(p.score)}</span>
                </li>
              ))}
              {Array.from({ length: Math.max(0, r.seats - r.filled) }, (_, i) => (
                <li key={`empty-${i}`} className={styles.empty}>
                  Empty seat
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>

      <AssignmentActions driveId={drive.id} result={result} />
    </>
  );
}
