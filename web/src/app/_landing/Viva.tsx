"use client";

import { useEffect, useRef, useState } from "react";
import { CodePanel } from "@/components/CodePanel";
import { Seal } from "@/components/Seal";
import styles from "./landing.module.css";

const CODE = `import pandas as pd

from .clean import clean_orders, CATALOGUE_PRICE

STORE_COLUMNS = ["store_id", "store_name", "city"]


def load_stores(path: str) -> pd.DataFrame:
    stores = pd.read_csv(path, usecols=STORE_COLUMNS)
    return stores.drop_duplicates("store_id")

def monthly_sales(orders, stores):
    """Revenue per store per month, including stores that sold nothing."""
    orders = orders.assign(month=orders["ordered_at"].dt.to_period("M"))
    merged = stores.merge(orders, on="store_id", how="left")
    merged["revenue"] = merged["quantity"].fillna(0) * merged["unit_price"].fillna(0)

    by_month = merged.groupby(["store_id", "month"], dropna=False)["revenue"].sum()
    return by_month.reset_index()


def best_month(by_month: pd.DataFrame) -> pd.DataFrame:
    idx = by_month.groupby("store_id")["revenue"].idxmax()
    return by_month.loc[idx, ["store_id", "month", "revenue"]]


def to_report(by_month: pd.DataFrame, path: str) -> None:
    by_month.to_csv(path, index=False)`;

const QUESTION = "Why is this a left merge on store_id, and what would the report show if you made it an inner merge?";

const ANSWER =
  "Stores is on the left so every store keeps a row, even one with no orders that month. Those rows come back with NaN quantity and price, which is why I fillna(0) before multiplying. With an inner merge the store would just vanish from the report, and a manager would read a closed store as missing data instead of a zero.";

/** The ideas the rubric listens for, and the word in the answer that satisfies each. */
const RUBRIC = [
  { idea: "Left merge keeps every store", at: "every store" },
  { idea: "Unmatched rows come back empty", at: "NaN" },
  { idea: "Inner merge drops them", at: "vanish" },
  { idea: "Names the code: fillna, store_id", at: "fillna(0)" },
];

const SCORE = 84;

type Phase = "asking" | "typing" | "grading" | "done";

/**
 * A recorded Ownership Check, played once: the question, the answer being written, the rubric
 * lighting up as the answer meets each idea, then the seal. With reduced motion it shows the
 * finished exchange at once.
 */
export function Viva() {
  const [typed, setTyped] = useState(0);
  const [phase, setPhase] = useState<Phase>("asking");
  const [run, setRun] = useState(0);
  const timers = useRef<number[]>([]);

  useEffect(() => {
    const still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    timers.current.forEach(clearTimeout);
    timers.current = [];
    if (still) {
      setTyped(ANSWER.length);
      setPhase("done");
      return;
    }
    setTyped(0);
    setPhase("asking");
    const later = (ms: number, f: () => void) => timers.current.push(window.setTimeout(f, ms));
    later(1100, () => {
      setPhase("typing");
      let i = 0;
      const step = () => {
        // Words arrive in bursts, like someone typing, not a ticker.
        i = Math.min(ANSWER.length, i + 1 + Math.floor(Math.random() * 3));
        setTyped(i);
        if (i < ANSWER.length) later(ANSWER[i - 1] === " " ? 34 : 16, step);
        else later(450, () => {
          setPhase("grading");
          later(900, () => setPhase("done"));
        });
      };
      step();
    });
    return () => timers.current.forEach(clearTimeout);
  }, [run]);

  const text = ANSWER.slice(0, typed);
  const words = text.trim() ? text.trim().split(/\s+/).length : 0;

  return (
    <figure className={styles.viva} aria-label="An example Ownership Check">
      <div className={styles.vivaBar}>
        <span className={styles.vivaRepo}>asha-r/sales-dashboard</span>
        <span className={styles.vivaSteps} aria-hidden="true">
          {[1, 2, 3, 4, 5].map((n) => (
            <i key={n} className={n === 1 ? styles.vivaStepDone : n === 2 ? styles.vivaStepNow : undefined} />
          ))}
        </span>
        <span className={styles.vivaClock}>{phase === "done" ? "8:42" : "9:16"}</span>
      </div>
      <div className={styles.vivaBody}>
        <CodePanel path="etl/monthly_sales.py" code={CODE} language="python" startLine={7} focus={[18, 25]} className={styles.vivaCode} />
        <div className={styles.vivaTalk}>
          <p className={styles.vivaMeta}>
            Question 2 of 5 <span>Joins</span>
          </p>
          <p className={styles.vivaQ}>{QUESTION}</p>
          <div className={styles.vivaAnswer} aria-live="off">
            <p>
              {text}
              {phase === "typing" || phase === "asking" ? <span className={styles.caret} aria-hidden="true" /> : null}
            </p>
            <span className={styles.vivaWords}>{words} words</span>
          </div>
          <ul className={styles.rubric} aria-label="What the grader listened for">
            {RUBRIC.map((r) => {
              const met = text.includes(r.at);
              return (
                <li key={r.idea} className={met ? styles.met : undefined}>
                  <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
                    <circle cx="8" cy="8" r="7" />
                    {met ? <path d="M4.5 8.2l2.2 2.2 4.8-4.9" /> : null}
                  </svg>
                  {r.idea}
                </li>
              );
            })}
          </ul>
          <div className={styles.vivaVerdict}>
            {phase === "done" ? (
              <>
                <Seal score={SCORE} size={112} stamp />
                <p>
                  <strong>sales-dashboard is defended.</strong> SQL, Python and pandas move to the Defended level on Asha&rsquo;s profile.
                  <button type="button" className={styles.replay} onClick={() => setRun((n) => n + 1)}>
                    Play it again
                  </button>
                </p>
              </>
            ) : (
              <p className={styles.vivaPending}>{phase === "grading" ? "Grading all five answers…" : "Asha is answering…"}</p>
            )}
          </div>
        </div>
      </div>
    </figure>
  );
}
