import { CodePanel, linesHref } from "@/components/CodePanel";
import { Seal } from "@/components/Seal";
import { shortDate } from "@/lib/format";
import type { CheckView } from "@/lib/portfolio-types";
import styles from "./transcript.module.css";

const PASS = 70;

function minutes(ms: number | null) {
  if (!ms) return null;
  const s = Math.round(ms / 1000);
  return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${String(s % 60).padStart(2, "0")} s`;
}

function words(text: string | null) {
  return text ? text.trim().split(/\s+/).filter(Boolean).length : 0;
}

/** How the questions were written and graded, in plain words. */
export function provenance(c: CheckView) {
  const asked = c.questionsBy === "model" ? "Questions written by an AI model" : "Questions written from Notch's concept templates";
  const graded =
    c.gradedBy === "model" ? "graded by an AI model" : c.gradedBy === "mixed" ? "graded by an AI model and Notch's rubric" : "graded against Notch's rubric";
  return `${asked}, ${graded}.`;
}

/** The verdict block: the seal for a defended repository, the score otherwise. */
export function Verdict({ check, stamp = false, who, children }: { check: CheckView; stamp?: boolean; who?: string; children?: React.ReactNode }) {
  const score = check.score ?? 0;
  return (
    <section className={styles.verdict} aria-labelledby="verdict-title">
      {check.defended ? (
        <Seal score={score} size={152} stamp={stamp} tone="paper" />
      ) : (
        <div className={styles.scoreRing} style={{ ["--p" as string]: `${score}` }} role="img" aria-label={`Score ${score} out of 100`}>
          <span>{score}</span>
        </div>
      )}
      <div className={styles.verdictText}>
        <h1 id="verdict-title" className={styles.verdictTitle}>
          {check.defended ? (
            <>
              {who ?? "You"} defended <span className={styles.repo}>{check.repo}</span>.
            </>
          ) : (
            <>
              <span className={styles.repo}>{check.repo}</span> isn&rsquo;t defended yet.
            </>
          )}
        </h1>
        {check.summary ? <p className={styles.summary}>{check.summary}</p> : null}
        <p className={styles.meta}>
          {check.questions.length} questions, chosen to touch {check.planCovered} of the {check.planElements} skills, concepts and files in the
          repository. {provenance(check)} {check.finishedAt ? `Finished ${shortDate(check.finishedAt)}.` : ""}
        </p>
        {children}
      </div>
    </section>
  );
}

/** Every exchange of a finished check. */
export function Exchanges({ check }: { check: CheckView }) {
  return (
    <ol className={styles.exchanges}>
      {check.questions.map((q) => {
        const score = q.score ?? 0;
        return (
          <li key={q.position} className={styles.exchange}>
            <div className={styles.codeCol}>
              <CodePanel
                path={q.path}
                code={q.code}
                language={q.language}
                startLine={q.startLine}
                href={linesHref(check.repoUrl, q.path, q.startLine, q.endLine)}
                maxHeight="26rem"
              />
            </div>
            <div className={styles.talk}>
              <p className={styles.qNum}>
                Question {q.position + 1}
                {q.concept ? <span className={styles.concept}>{q.concept}</span> : null}
              </p>
              <p className={styles.prompt}>{q.prompt}</p>
              {q.answer ? (
                <blockquote className={styles.answer}>
                  <p>{q.answer}</p>
                  <footer>
                    {words(q.answer)} words{minutes(q.millis) ? `, written in ${minutes(q.millis)}` : ""}
                  </footer>
                </blockquote>
              ) : (
                <p className={styles.unanswered}>Not answered.</p>
              )}
              <div className={styles.grade}>
                <span className={`${styles.gradeScore} ${score >= PASS ? styles.gradePass : ""}`}>{score}</span>
                <span className={styles.gradeBar} aria-hidden="true">
                  <span style={{ width: `${score}%` }} />
                  <i style={{ left: `${PASS}%` }} />
                </span>
              </div>
              {q.feedback ? <p className={styles.feedback}>{q.feedback}</p> : null}
              {q.flags.length > 0 ? (
                <ul className={styles.flags} aria-label="Integrity signals">
                  {q.flags.map((f) => (
                    <li key={f}>{f}</li>
                  ))}
                </ul>
              ) : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
