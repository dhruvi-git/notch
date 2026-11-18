"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { NotchMark } from "@/components/Brand";
import { CodePanel, linesHref } from "@/components/CodePanel";
import { Exchanges, Verdict } from "@/components/ownership/Transcript";
import { call, ClientApiError, postJson } from "@/lib/client-api";
import type { CheckView } from "@/lib/portfolio-types";
import styles from "./interview.module.css";

type Draft = { answer: string; millis: number; pastes: number; focusLost: number; dirty: boolean };
type SaveState = "idle" | "saving" | "saved" | "error";

const LOW_SECONDS = 120;

function clock(s: number) {
  const m = Math.floor(s / 60);
  return `${m}:${String(s % 60).padStart(2, "0")}`;
}

function wordCount(t: string) {
  return t.trim() ? t.trim().split(/\s+/).length : 0;
}

/** A timed Ownership Check: one question at a time about the candidate's own code. */
export function Interview({ initial }: { initial: CheckView }) {
  const [check, setCheck] = useState(initial);
  const [justFinished, setJustFinished] = useState(false);

  if (check.status === "FINISHED") return <Result check={check} stamp={justFinished} />;
  return (
    <Session
      check={check}
      onFinished={(c) => {
        setJustFinished(true);
        setCheck(c);
        window.scrollTo({ top: 0 });
      }}
    />
  );
}

function Session({ check, onFinished }: { check: CheckView; onFinished: (c: CheckView) => void }) {
  const qs = check.questions;
  const [at, setAt] = useState(() => Math.max(0, qs.findIndex((q) => !q.answer)));
  const [drafts, setDrafts] = useState<Draft[]>(() =>
    qs.map((q) => ({ answer: q.answer ?? "", millis: q.millis ?? 0, pastes: 0, focusLost: 0, dirty: false })),
  );
  const [left, setLeft] = useState(check.secondsLeft);
  const [save, setSave] = useState<SaveState>("idle");
  const [confirming, setConfirming] = useState(false);
  const [finishing, setFinishing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const draftsRef = useRef(drafts);
  draftsRef.current = drafts;
  const atRef = useRef(at);
  atRef.current = at;
  const since = useRef(Date.now());
  const deadline = useRef(Date.now() + check.secondsLeft * 1000);
  const finished = useRef(false);
  const textarea = useRef<HTMLTextAreaElement>(null);

  const update = useCallback((i: number, patch: Partial<Draft>) => {
    setDrafts((d) => d.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  }, []);

  /** Adds the time spent on the current question since the last tick. */
  const tick = useCallback(() => {
    const now = Date.now();
    if (document.visibilityState === "visible") {
      const i = atRef.current;
      const spent = now - since.current;
      setDrafts((d) => d.map((x, j) => (j === i ? { ...x, millis: x.millis + spent } : x)));
    }
    since.current = now;
  }, []);

  const persist = useCallback(
    async (i: number) => {
      const d = draftsRef.current[i];
      if (!d || !d.dirty || finished.current) return true;
      setSave("saving");
      try {
        await postJson(`/ownership/checks/${check.id}/answers/${qs[i].position}`, {
          answer: d.answer,
          millis: Math.round(d.millis),
          pastes: d.pastes,
          focusLost: d.focusLost,
        }, "PUT");
        setDrafts((all) => all.map((x, j) => (j === i && x.answer === d.answer ? { ...x, dirty: false } : x)));
        setSave("saved");
        return true;
      } catch (e) {
        setSave("error");
        if (e instanceof ClientApiError && (e.code === "time_up" || e.code === "check_finished")) await finish(true);
        return false;
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [check.id, qs],
  );

  const finish = useCallback(
    async (force = false) => {
      if (finished.current) return;
      setFinishing(true);
      setError(null);
      tick();
      if (!force) for (let i = 0; i < qs.length; i++) await persist(i);
      try {
        finished.current = true;
        const done = await call<CheckView>(`/ownership/checks/${check.id}/finish`, { method: "POST" });
        onFinished(done);
      } catch (e) {
        finished.current = false;
        setFinishing(false);
        setError(e instanceof ClientApiError ? e.message : "The check couldn’t be finished. Try again.");
      }
    },
    [check.id, onFinished, persist, qs.length, tick],
  );

  // The clock, from the server's deadline.
  useEffect(() => {
    const t = window.setInterval(() => {
      const s = Math.max(0, Math.round((deadline.current - Date.now()) / 1000));
      setLeft(s);
      if (s === 0) void finish();
    }, 500);
    return () => window.clearInterval(t);
  }, [finish]);

  // Time on task, and leaving the page.
  useEffect(() => {
    const onHide = () => {
      tick();
      if (document.visibilityState === "hidden") update(atRef.current, { focusLost: draftsRef.current[atRef.current].focusLost + 1, dirty: true });
    };
    const onBlur = () => {
      if (document.visibilityState === "visible") update(atRef.current, { focusLost: draftsRef.current[atRef.current].focusLost + 1, dirty: true });
    };
    const t = window.setInterval(tick, 5000);
    document.addEventListener("visibilitychange", onHide);
    window.addEventListener("blur", onBlur);
    return () => {
      window.clearInterval(t);
      document.removeEventListener("visibilitychange", onHide);
      window.removeEventListener("blur", onBlur);
    };
  }, [tick, update]);

  // Autosave a moment after typing stops.
  const current = drafts[at];
  useEffect(() => {
    if (!current?.dirty) return;
    const t = window.setTimeout(() => void persist(at), 1500);
    return () => window.clearTimeout(t);
  }, [current?.answer, current?.dirty, at, persist]);

  async function go(to: number) {
    if (to === at || to < 0 || to >= qs.length) return;
    tick();
    void persist(at);
    setAt(to);
    setConfirming(false);
    requestAnimationFrame(() => textarea.current?.focus());
  }

  const q = qs[at];
  const answered = drafts.filter((d) => wordCount(d.answer) > 0).length;
  const low = left <= LOW_SECONDS;

  return (
    <div className={styles.room}>
      <header className={`${styles.bar} on-brand`}>
        <Link href="/start" className={styles.leave} aria-label="Leave the check and go to your profile. The clock keeps running.">
          <NotchMark size={20} />
        </Link>
        <div className={styles.repo}>
          <span className={styles.repoLabel}>Ownership Check</span>
          <span className={styles.repoName}>{check.repo}</span>
        </div>
        <nav className={styles.steps} aria-label="Questions">
          {qs.map((x, i) => (
            <button
              key={x.position}
              type="button"
              className={`${styles.step} ${i === at ? styles.stepNow : ""} ${wordCount(drafts[i].answer) > 0 ? styles.stepDone : ""}`}
              aria-current={i === at ? "step" : undefined}
              aria-label={`Question ${i + 1}${wordCount(drafts[i].answer) > 0 ? ", answered" : ""}`}
              onClick={() => go(i)}
            >
              {i + 1}
            </button>
          ))}
        </nav>
        <div className={`${styles.clock} ${low ? styles.clockLow : ""}`} role="timer" aria-live={low ? "polite" : "off"} aria-label={`${clock(left)} left`}>
          {clock(left)}
        </div>
        <button type="button" className="btn btn-yellow" onClick={() => (answered < qs.length ? setConfirming(true) : finish())} disabled={finishing}>
          {finishing ? "Grading…" : "Finish"}
        </button>
      </header>

      {confirming ? (
        <div className={styles.confirm} role="alertdialog" aria-labelledby="confirm-text">
          <p id="confirm-text">
            {qs.length - answered} of {qs.length} questions have no answer yet. Unanswered questions score 0.
          </p>
          <button type="button" className="btn btn-quiet" onClick={() => setConfirming(false)}>
            Keep answering
          </button>
          <button type="button" className="btn btn-primary" onClick={() => finish()} disabled={finishing}>
            Finish anyway
          </button>
        </div>
      ) : null}

      <main className={styles.stage}>
        <div className={styles.codeSide}>
          <CodePanel
            key={q.position}
            path={q.path}
            code={q.code}
            language={q.language}
            startLine={q.startLine}
            href={linesHref(check.repoUrl, q.path, q.startLine, q.endLine)}
            className={styles.code}
          />
        </div>

        <section className={styles.askSide} aria-labelledby="prompt" key={q.position}>
          <p className={styles.qMeta}>
            Question {at + 1} of {qs.length}
            {q.concept ? <span className={styles.concept}>{q.concept}</span> : null}
          </p>
          <h1 id="prompt" className={styles.prompt}>
            {q.prompt}
          </h1>
          {q.covers.length > 0 ? <p className={styles.covers}>Touches {q.covers.join(", ")}</p> : null}

          <label htmlFor="answer" className="sr-only">
            Your answer
          </label>
          <textarea
            id="answer"
            ref={textarea}
            className={styles.answer}
            value={current.answer}
            maxLength={5000}
            placeholder="Explain it the way you would to a teammate: what this code does, why you wrote it this way, and what would break if it changed."
            onChange={(e) => update(at, { answer: e.target.value, dirty: true })}
            onPaste={() => update(at, { pastes: current.pastes + 1, dirty: true })}
            onBlur={() => void persist(at)}
            autoFocus
            spellCheck
          />
          <div className={styles.under}>
            <span>
              {wordCount(current.answer)} words
              <span className={styles.saveState} aria-live="polite">
                {save === "saving" ? "Saving…" : save === "saved" && !current.dirty ? "Saved" : save === "error" ? "Not saved. Retrying when you type." : ""}
              </span>
            </span>
            <span className={styles.nav}>
              <button type="button" className="btn btn-quiet" onClick={() => go(at - 1)} disabled={at === 0}>
                Previous
              </button>
              {at < qs.length - 1 ? (
                <button type="button" className="btn btn-primary" onClick={() => go(at + 1)}>
                  Next question
                </button>
              ) : (
                <button type="button" className="btn btn-primary" onClick={() => (answered < qs.length ? setConfirming(true) : finish())} disabled={finishing}>
                  Finish and grade
                </button>
              )}
            </span>
          </div>
          {error ? (
            <p className={styles.error} role="alert">
              {error}
            </p>
          ) : null}
          <p className={styles.rules}>
            Write in your own words. Pasting text, leaving this page and typing faster than a person can are recorded, and recruiters see them on the
            transcript.
          </p>
        </section>
      </main>
    </div>
  );
}

function Result({ check, stamp }: { check: CheckView; stamp: boolean }) {
  return (
    <div className={styles.result}>
      <header className={`${styles.resultHead} on-brand`}>
        <div className={styles.resultInner}>
          <Link href="/start" className={styles.back}>
            <NotchMark size={20} /> Back to my profile
          </Link>
          <Verdict check={check} stamp={stamp}>
          <div className={styles.resultActions}>
            {check.defended ? (
              <>
                <Link href={`/t/${check.id}`} className="btn btn-yellow">
                  Open the public transcript
                </Link>
                <Link href="/start" className="btn btn-quiet">
                  See it on my profile
                </Link>
              </>
            ) : (
              <>
                <Link href="/start" className="btn btn-yellow">
                  Back to my profile
                </Link>
                <span className={styles.retake}>You can take another check on this repository in 24 hours.</span>
              </>
            )}
          </div>
          </Verdict>
        </div>
      </header>
      <main className={styles.resultBody}>
        <h2 className={styles.resultTitle}>How each answer was graded</h2>
        <Exchanges check={check} />
      </main>
    </div>
  );
}
