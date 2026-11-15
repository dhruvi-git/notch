"use client";

import { useEffect, useState } from "react";
import styles from "./landing.module.css";

const CODE = [
  { n: 1, text: 'import mongoose from "mongoose";', flag: true },
  { n: 2, text: "" },
  { n: 3, text: "export async function saveEvent(e: Event) {" },
  { n: 4, text: "  await mongoose.connect(process.env.MONGO_URL);", second: true },
  { n: 5, text: "  await EventModel.create(e);" },
  { n: 6, text: "}" },
];

/**
 * The page's one orchestrated moment: Notch reviews a file. The line that goes against a
 * decision is swept with the highlighter, then the review notes arrive. With reduced motion the
 * finished review is shown at once.
 */
export function HeroDemo() {
  const [stage, setStage] = useState(0);  // 0 code, 1 highlight, 2 first note, 3 second note

  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      setStage(3);
      return;
    }
    const timers = [
      setTimeout(() => setStage(1), 700),
      setTimeout(() => setStage(2), 1500),
      setTimeout(() => setStage(3), 2600),
    ];
    return () => timers.forEach(clearTimeout);
  }, []);

  return (
    <div className={styles.demo} role="img" aria-label="Example: Notch reviewing a file">
      <div className={styles.file} aria-hidden="true">
        <p className={styles.fileName}>src/events/store.ts</p>
        <pre className={styles.fileCode}>
          {CODE.map((l) => (
            <span key={l.n} className={styles.codeLine}>
              <span className={styles.lineNo}>{l.n}</span>
              <span className={l.flag && stage >= 1 ? styles.sweep : l.second && stage >= 3 ? styles.underline : undefined}>
                {l.text || " "}
              </span>
            </span>
          ))}
        </pre>
      </div>
      <div className={styles.note} data-show={stage >= 2} aria-hidden="true">
        <p className={styles.noteHead}>
          <span className="chip chip-danger">High</span>
          <span>Goes against a decision, line 1</span>
        </p>
        <p>On 5 October you chose Postgres for all data and ruled out MongoDB. This file brings MongoDB back.</p>
      </div>
      <div className={styles.note} data-show={stage >= 3} aria-hidden="true">
        <p className={styles.noteHead}>
          <span className="chip chip-warn">Medium</span>
          <span>Performance, line 4</span>
        </p>
        <p>Connects to the database on every write. Open one connection when the app starts.</p>
      </div>
      <p className="sr-only">
        Notch reviews src/events/store.ts. It flags line 1, which imports MongoDB, as going against the project&rsquo;s
        decision to use Postgres, and line 4 for opening a new database connection on every write.
      </p>
    </div>
  );
}
