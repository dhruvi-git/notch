"use client";

import { Highlighted } from "@/components/Highlighted";
import { parseAnswer, type AnswerPart } from "@/lib/answer";
import { plural, ROLE_LABEL, shortDate } from "@/lib/format";
import type { AskResponse } from "@/lib/types";
import styles from "./recall.module.css";

const QUOTE_CONTEXT = 260;

function Parts({ parts }: { parts: AnswerPart[] }) {
  return (
    <>
      {parts.map((p, i) =>
        p.kind === "text" ? (
          <span key={i}>{p.text}</span>
        ) : (
          <a key={i} href={`#source-${p.n}`} className={styles.cite} aria-label={`Source ${p.n}`}>
            {p.n}
          </a>
        ),
      )}
    </>
  );
}

/** The written answer, then each source with the exact quoted span highlighted. */
export function Answer({ answer }: { answer: AskResponse }) {
  const parsed = parseAnswer(answer.answer);
  const sources = [...new Map(answer.citations.map((c) => [c.n, c])).values()].sort((a, b) => a.n - b.n);
  return (
    <section className={styles.answer} aria-label="Answer">
      <div className={styles.answerBody} data-grounded={answer.grounded}>
        {parsed.intro ? (
          <p className={styles.answerIntro}>
            <Parts parts={parsed.intro} />
          </p>
        ) : null}
        {parsed.items.length > 0 ? (
          <ul className={styles.answerList}>
            {parsed.items.map((item, i) => (
              <li key={i}>
                <Parts parts={item} />
              </li>
            ))}
          </ul>
        ) : null}
        {parsed.paragraphs.map((p, i) => (
          <p key={i}>
            <Parts parts={p} />
          </p>
        ))}
      </div>

      {sources.length > 0 ? (
        <ol className={styles.sources} aria-label="Sources">
          {sources.map((c) => {
            const quoteSpans = answer.citations.filter((x) => x.n === c.n).map((x) => [x.start, x.end] as const);
            return (
              <li key={c.n} id={`source-${c.n}`} className={styles.source}>
                <span className={styles.sourceNumber} aria-hidden="true">
                  {c.n}
                </span>
                <div>
                  <p className={styles.sourceText}>
                    <Highlighted text={c.text} spans={quoteSpans} length={QUOTE_CONTEXT} />
                  </p>
                  <p className={styles.sourceMeta}>
                    {c.source.conversationTitle}, {shortDate(c.source.sentAt)}, {ROLE_LABEL[c.source.role] ?? c.source.role}
                  </p>
                </div>
              </li>
            );
          })}
        </ol>
      ) : null}
      <p className={styles.answerFoot}>
        {answer.grounded
          ? `Written from ${plural(answer.contextChunks, "passage")} in this project. Every line is linked to its source.`
          : "Nothing was sent for an answer, so this didn't use a question."}
      </p>
    </section>
  );
}
