import type { CSSProperties } from "react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { CountUp } from "@/components/CountUp";
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

/** A stagger index for the shared motion classes in globals.css. */
const at = (i: number) => ({ "--i": i }) as CSSProperties;

type Method = { key: string; name: string; detail: string; filled: number; score: number | null; best: boolean };

function Result({ drive, result }: { drive: Drive; result: Assignment }) {
  const all = result.filled === result.totalSeats;
  const pct = (n: number) => (result.totalSeats === 0 ? 0 : (100 * n) / result.totalSeats);
  // What recruiters see is the outcome, not the method: Notch's assignment against the usual
  // approach of handing each seat to the highest scorer still free.
  const methods: Method[] = [
    {
      key: "simple",
      name: "Top score first",
      detail: "each seat to the highest scorer still free",
      filled: result.baselineFilled,
      score: result.baselineMatch,
      best: false,
    },
    {
      key: "notch",
      name: "Notch",
      detail: "every seat it can fill, then the best overall match",
      filled: result.filled,
      score: result.totalMatch,
      best: true,
    },
  ];
  const [simple, notch] = methods;
  const gained = notch.filled - simple.filled;
  const moved = result.roles.reduce((n, r) => n + r.placements.filter((p) => p.moved).length, 0);
  const hasMoves = result.totalMatch != null;
  let chip = 6;

  return (
    <>
      <h1 className={`${common.title} rise`}>
        {all ? `All ${result.totalSeats} seats filled` : `${result.filled} of ${result.totalSeats} seats filled`}
      </h1>

      <div className={styles.summary}>
        <div className={`${styles.compare} rise`} style={at(1)}>
          <div className={styles.compareBars} role="table" aria-label="Notch's assignment compared with filling seats top score first">
            <div className={`${styles.compareRow} ${styles.compareHead}`} role="row">
              <span role="columnheader">Approach</span>
              <span role="columnheader">Seats filled</span>
              <span role="columnheader" className={styles.compareNum}>
                Seats
              </span>
              <span role="columnheader" className={styles.compareNum}>
                Total match
              </span>
            </div>
            {methods.map((m, i) => (
              <div key={m.key} className={`${styles.compareRow} ${m.best ? styles.compareBest : ""}`} role="row">
                <span role="cell" className={styles.compareName}>
                  {m.best ? <strong>{m.name}</strong> : m.name}
                  <span className={styles.compareDetail}>{m.detail}</span>
                </span>
                <span role="cell" className={styles.compareTrack} aria-label={`${m.filled} of ${result.totalSeats} seats`}>
                  <span className={`${m.best ? styles.compareFill : styles.compareFillGreedy} grow-x`} style={{ ...at(i), width: `${pct(m.filled)}%` }} />
                </span>
                <span role="cell" className={styles.compareNum}>
                  <CountUp value={m.filled} delay={150 + i * 180} />/{result.totalSeats}
                </span>
                <span role="cell" className={styles.compareNum}>
                  {m.score === null ? "–" : <CountUp value={Math.round(m.score)} delay={150 + i * 180} />}
                </span>
              </div>
            ))}
          </div>
          <p className={styles.compareText}>
            {gained > 0
              ? `Handing each seat to the top scorer still free would leave ${gained} more seat${gained === 1 ? "" : "s"} empty. Notch fills ${gained === 1 ? "it" : "them"} by placing versatile candidates in the roles only they can fill, then arranges everyone for the best overall match.`
              : "Handing each seat to the top scorer still free fills as many seats here. Notch then arranges everyone for the best overall match."}
            {!all ? " Some seats stay empty because not enough candidates meet those roles' minimum match." : ""}
          </p>
        </div>
        <div className={`${common.card} ${styles.facts} rise`} style={at(2)}>
          {hasMoves && moved > 0 ? (
            <span className={common.muted}>
              <strong>{moved}</strong> {moved === 1 ? "person" : "people"} placed differently than top score first would
            </span>
          ) : null}
          <span className={common.muted}>
            <strong>{result.roles.length}</strong> role{result.roles.length === 1 ? "" : "s"}, <strong>{result.totalSeats}</strong> seat
            {result.totalSeats === 1 ? "" : "s"}
          </span>
          <span className={common.muted}>Assigned {new Date(result.runAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}</span>
        </div>
      </div>

      <div className={styles.roleGrid}>
        {result.roles.map((r, ri) => (
          <section key={r.roleId} className={`${common.card} rise`} style={at(3 + ri)} aria-labelledby={`role-${r.roleId}`}>
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
                <li key={p.userId} className={`${styles.person} ${p.moved ? styles.personMoved : ""} pop-in`} style={at(chip++)}>
                  <Link href={`/u/${p.handle}`}>@{p.handle}</Link>
                  {p.moved ? (
                    <span className={styles.movedTag} title="Filling seats top score first would have left this person out or put them in another role">
                      moved by Notch
                    </span>
                  ) : null}
                  <span className={styles.personScore}>{Math.round(p.score)}</span>
                </li>
              ))}
              {Array.from({ length: Math.max(0, r.seats - r.filled) }, (_, i) => (
                <li key={`empty-${i}`} className={`${styles.empty} pop-in`} style={at(chip++)}>
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
