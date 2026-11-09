"use client";

import { useEffect, useState } from "react";
import styles from "./landing.module.css";

const QUESTION = "What did we decide about the database?";

/**
 * The page's one orchestrated moment: a question types itself, the answer arrives with its
 * source highlighted by a marker stroke, then a drift warning appears. With reduced motion,
 * the finished state is shown at once.
 */
export function HeroDemo() {
  const [typed, setTyped] = useState(0);
  const [stage, setStage] = useState(0);  // 0 typing, 1 answer, 2 drift

  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      setTyped(QUESTION.length);
      setStage(2);
      return;
    }
    let i = 0;
    const timers: ReturnType<typeof setTimeout>[] = [];
    const tick = () => {
      i += 1;
      setTyped(i);
      if (i < QUESTION.length) timers.push(setTimeout(tick, 38 + Math.random() * 40));
      else {
        timers.push(setTimeout(() => setStage(1), 450));
        timers.push(setTimeout(() => setStage(2), 2300));
      }
    };
    timers.push(setTimeout(tick, 600));
    return () => timers.forEach(clearTimeout);
  }, []);

  return (
    <div className={styles.demo} aria-label="Example: asking Notch a question" role="img">
      <div className={styles.demoSearch} aria-hidden="true">
        <svg width="18" height="18" viewBox="0 0 20 20">
          <circle cx="8.5" cy="8.5" r="5.75" fill="none" stroke="currentColor" strokeWidth="1.6" />
          <path d="m13 13 4.25 4.25" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
        <span>
          {QUESTION.slice(0, typed)}
          {stage === 0 ? <span className={styles.caret} /> : null}
        </span>
      </div>
      <div className={styles.demoAnswer} data-show={stage >= 1} aria-hidden="true">
        <p className={styles.demoIntro}>Here&rsquo;s what this project decided:</p>
        <p className={styles.demoLine}>
          <span className={stage >= 1 ? styles.sweep : undefined}>
            Let&rsquo;s go with Postgres instead of MongoDB
          </span>{" "}
          because we need joins and real transactions for payments.<sup>1</sup>
        </p>
        <p className={styles.demoSource}>1 &nbsp;Billing service: choosing a database, 5 October</p>
      </div>
      <div className={styles.demoDrift} data-show={stage >= 2} aria-hidden="true">
        <p className={styles.demoDriftLabel}>Drift</p>
        <p>
          A new chat says <code>npm install mongoose</code>. That goes against decision 1.
        </p>
      </div>
      <p className="sr-only">
        Asked &ldquo;What did we decide about the database?&rdquo;, Notch answers with the decision to use Postgres
        instead of MongoDB, cites the conversation it came from, and later flags a chat that installs MongoDB as going
        against that decision.
      </p>
    </div>
  );
}
