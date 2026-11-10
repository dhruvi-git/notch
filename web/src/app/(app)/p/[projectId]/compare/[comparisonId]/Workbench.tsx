"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { ClientApiError, postJson } from "@/lib/client-api";
import type { ComparisonAnalysis, ComparisonMatrix, ComparisonView, Memory } from "@/lib/types";
import styles from "./workbench.module.css";

const SAVE_DELAY = 450;

function newId(prefix: string) {
  return prefix + Math.random().toString(36).slice(2, 8);
}

function pct(share: number) {
  return Math.round(share * 100);
}

export function Workbench({ projectId, initial }: { projectId: string; initial: ComparisonView }) {
  const [matrix, setMatrix] = useState<ComparisonMatrix>(initial.comparison);
  const [view, setView] = useState<ComparisonView>(initial);
  const [saving, setSaving] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [error, setError] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latest = useRef(matrix);
  const decided = view.status === "DECIDED";

  // Autosave: every change is saved shortly after you stop, and the Java analysis comes back.
  function change(next: ComparisonMatrix) {
    setMatrix(next);
    latest.current = next;
    setSaving("saving");
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      try {
        const v = await postJson<ComparisonView>(`/comparisons/${view.id}`, latest.current, "PUT");
        if (latest.current === next) setView(v);
        setSaving("saved");
        setError(null);
      } catch (err) {
        setSaving("error");
        setError(err instanceof ClientApiError ? err.message : "Changes weren't saved. Check your connection.");
      }
    }, SAVE_DELAY);
  }
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  const setScore = (critId: string, optId: string, value: number | null) => {
    const row = { ...(matrix.scores[critId] ?? {}) };
    if (value === null) delete row[optId];
    else row[optId] = value;
    change({ ...matrix, scores: { ...matrix.scores, [critId]: row } });
  };
  const a = view.analysis;
  const leaderId = a.winnerIds.length === 1 ? a.winnerIds[0] : null;
  const percentOf = new Map(a.standings.map((s) => [s.optionId, s.percent]));

  return (
    <div className={styles.page}>
      <p className={styles.back}>
        <Link href={`/p/${projectId}/compare`}>All comparisons</Link>
      </p>

      <header className={styles.head}>
        <label className="sr-only" htmlFor="question">Question</label>
        <input id="question" className={styles.question} value={matrix.question} disabled={decided} maxLength={200}
          onChange={(e) => change({ ...matrix, question: e.target.value })} />
        <p className={styles.status} aria-live="polite">
          {decided ? <span className="chip chip-ok">Decided: {view.chosen}</span>
            : saving === "saving" ? "Saving…" : saving === "saved" ? "All changes saved" : saving === "error" ? "Not saved" : "Changes save as you go"}
        </p>
      </header>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}

      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <caption className="sr-only">Options scored against weighted criteria</caption>
          <thead>
            <tr>
              <th scope="col" className={styles.critHead}>What matters</th>
              <th scope="col" className={styles.weightHead}>Weight</th>
              {matrix.options.map((o) => (
                <th key={o.id} scope="col" className={styles.optHead} data-leader={o.id === leaderId}>
                  <span className={styles.nameRow}>
                  <input className={styles.optName} value={o.name} disabled={decided} aria-label="Option name" maxLength={60}
                    onChange={(e) => change({ ...matrix, options: matrix.options.map((x) => x.id === o.id ? { ...x, name: e.target.value } : x) })} />
                  {!decided && matrix.options.length > 2 ? (
                    <button type="button" className={styles.remove} aria-label={`Remove ${o.name}`} title="Remove"
                      onClick={() => change({ ...matrix, options: matrix.options.filter((x) => x.id !== o.id) })}>
                      ×
                    </button>
                  ) : null}
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {matrix.criteria.map((c) => (
              <tr key={c.id}>
                <th scope="row" className={styles.critCell}>
                  <span className={styles.nameRow}>
                  <input className={styles.critName} value={c.name} disabled={decided} aria-label="Criterion" maxLength={60}
                    onChange={(e) => change({ ...matrix, criteria: matrix.criteria.map((x) => x.id === c.id ? { ...x, name: e.target.value } : x) })} />
                  {!decided && matrix.criteria.length > 1 ? (
                    <button type="button" className={styles.remove} aria-label={`Remove ${c.name}`} title="Remove"
                      onClick={() => {
                        const { [c.id]: _gone, ...scores } = matrix.scores;
                        change({ ...matrix, criteria: matrix.criteria.filter((x) => x.id !== c.id), scores });
                      }}>
                      ×
                    </button>
                  ) : null}
                  </span>
                </th>
                <td className={styles.weightCell}>
                  <Scale kind="weight" value={c.weight} disabled={decided} label={`Weight of ${c.name}`}
                    onChange={(v) => change({ ...matrix, criteria: matrix.criteria.map((x) => x.id === c.id ? { ...x, weight: v ?? 1 } : x) })} />
                </td>
                {matrix.options.map((o) => (
                  <td key={o.id} className={styles.scoreCell} data-leader={o.id === leaderId}>
                    <Scale kind="score" value={matrix.scores[c.id]?.[o.id] ?? null} disabled={decided}
                      label={`${o.name} on ${c.name}`} onChange={(v) => setScore(c.id, o.id, v)} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colSpan={2} className={styles.totalLabel}>Weighted score</th>
              {matrix.options.map((o) => {
                const p = percentOf.get(o.id);
                return (
                  <td key={o.id} className={styles.totalCell} data-leader={o.id === leaderId}>
                    <span className={styles.totalValue}>{p === undefined ? "–" : `${Math.round(p)}%`}</span>
                    <span className={styles.totalBar} aria-hidden="true"><span style={{ width: `${p ?? 0}%` }} /></span>
                    {decided && o.name === view.chosen ? <span className="chip chip-ok">Chosen</span>
                      : !decided && o.id === leaderId ? <span className="chip chip-yellow">Leading</span> : null}
                  </td>
                );
              })}
            </tr>
          </tfoot>
        </table>
      </div>

      {!decided ? (
        <div className={styles.adders}>
          <button type="button" className="btn btn-quiet" disabled={matrix.options.length >= 8}
            onClick={() => change({ ...matrix, options: [...matrix.options, { id: newId("o"), name: `Option ${matrix.options.length + 1}`, note: null }] })}>
            Add option
          </button>
          <button type="button" className="btn btn-quiet" disabled={matrix.criteria.length >= 12}
            onClick={() => change({ ...matrix, criteria: [...matrix.criteria, { id: newId("c"), name: "New criterion", weight: 3 }] })}>
            Add criterion
          </button>
        </div>
      ) : null}

      <Verdict analysis={a} />
      {decided ? <DecidedNote projectId={projectId} view={view} /> : (
        <Decide view={view} matrix={matrix} leaderId={leaderId} onDecided={(v) => setView(v)} />
      )}
    </div>
  );
}

/** Five steps you click: scores (1 = poor, 5 = excellent) or weights (1 = minor, 5 = essential). */
function Scale({ kind, value, onChange, disabled, label }: {
  kind: "score" | "weight";
  value: number | null;
  onChange: (v: number | null) => void;
  disabled: boolean;
  label: string;
}) {
  return (
    <div className={styles.scale} data-kind={kind} role="radiogroup" aria-label={label}>
      {[1, 2, 3, 4, 5].map((n) => (
        <button key={n} type="button" role="radio" aria-checked={value === n} disabled={disabled}
          aria-label={`${n} of 5`} className={styles.step} data-on={value !== null && n <= value}
          onClick={() => onChange(kind === "score" && value === n ? null : n)} />
      ))}
      <span className={styles.scaleValue} aria-hidden="true">{value ?? ""}</span>
    </div>
  );
}

function Verdict({ analysis: a }: { analysis: ComparisonAnalysis }) {
  if (a.problems.length) {
    return (
      <section className={styles.verdict} aria-labelledby="verdict-title">
        <h2 id="verdict-title">Before this can tell you anything</h2>
        <ul className={styles.problems}>{a.problems.map((p) => <li key={p}>{p}</li>)}</ul>
      </section>
    );
  }
  const top = a.robustness[0];
  const leader = a.winnerIds.length === 1 ? a.standings[0] : null;
  let headline: string;
  if (!leader) headline = `It's a tie at the current weights. ${top.name} comes out ahead in ${pct(top.share)}% of reweightings.`;
  else if (top.share >= 0.9) headline = `${leader.name} wins in ${pct(top.share)}% of ${a.samples.toLocaleString("en")} random reweightings. A clear choice.`;
  else if (top.share >= 0.6) headline = `${leader.name} wins in ${pct(top.share)}% of ${a.samples.toLocaleString("en")} random reweightings: fairly clear, but it depends on the weights.`;
  else headline = `A close call: ${leader.name} wins in only ${pct(top.share)}% of reweightings. Small changes to the weights change the winner.`;
  return (
    <section className={styles.verdict} aria-labelledby="verdict-title">
      <h2 id="verdict-title">How sure is this?</h2>
      <p className={styles.headline}>{headline}</p>
      {a.unscored > 0 ? <p className={styles.unscored}>{a.unscored} {a.unscored === 1 ? "cell is" : "cells are"} still empty and count as nothing.</p> : null}
      <dl className={styles.shares}>
        {a.robustness.map((s) => (
          <div key={s.optionId}>
            <dt>{s.name}</dt>
            <dd>
              <span className={styles.shareBar} aria-hidden="true"><span style={{ width: `${s.share * 100}%` }} /></span>
              <span className={styles.shareValue}>{pct(s.share)}%</span>
            </dd>
          </div>
        ))}
      </dl>
      {a.decisive.length && leader ? (
        <p className={styles.decisive}>
          What decides it:{" "}
          {a.decisive.map((d, i) => (
            <span key={d.criterionId}>
              {i > 0 ? ", " : ""}
              <strong>{d.name.toLowerCase()}</strong> (+{d.points})
            </span>
          ))}
        </p>
      ) : null}
      <p className={styles.method}>
        Each run multiplies every weight by a random factor between 0.5 and 1.5 and checks which option comes out on top.
      </p>
    </section>
  );
}

function Decide({ view, matrix, leaderId, onDecided }: {
  view: ComparisonView;
  matrix: ComparisonMatrix;
  leaderId: string | null;
  onDecided: (v: ComparisonView) => void;
}) {
  const a = view.analysis;
  const [choice, setChoice] = useState<string>(leaderId ?? matrix.options[0]?.id ?? "");
  const chosen = matrix.options.find((o) => o.id === choice);
  const share = a.robustness.find((r) => r.optionId === choice)?.share;
  const reasons = a.decisive.slice(0, 2).map((d) => d.name.toLowerCase());
  const [title, setTitle] = useState<string | null>(null);  // null: follow the suggestion
  const [why, setWhy] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (leaderId) setChoice(leaderId);
  }, [leaderId]);

  const defaultTitle = chosen ? `For \u201c${matrix.question}\u201d, we chose ${chosen.name}.` : "";
  const defaultWhy = chosen && choice === leaderId && reasons.length
    ? `Scored highest on ${reasons.join(" and ")}${share !== undefined ? `, and won in ${pct(share)}% of reweightings` : ""}.`
    : "";

  if (a.problems.length) return null;
  async function decide() {
    setBusy(true);
    setError(null);
    try {
      const r = await postJson<{ comparison: ComparisonView; decision: Memory }>(`/comparisons/${view.id}/decide`, {
        optionId: choice, title: (title ?? defaultTitle).trim() || defaultTitle, rationale: (why ?? defaultWhy).trim() || null,
      });
      onDecided(r.comparison);
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The decision wasn't recorded. Try again.");
      setBusy(false);
    }
  }

  return (
    <section className={styles.decide} aria-labelledby="decide-title">
      <h2 id="decide-title">Make it a decision</h2>
      <fieldset className={styles.choices}>
        <legend className="sr-only">Option to choose</legend>
        {matrix.options.map((o) => (
          <label key={o.id} className={styles.choice} data-on={o.id === choice}>
            <input type="radio" name="choice" value={o.id} checked={o.id === choice} onChange={() => setChoice(o.id)} className="sr-only" />
            {o.name}
          </label>
        ))}
      </fieldset>
      <label className={styles.decideLabel}>
        <span>The decision</span>
        <input className="field" value={title ?? defaultTitle} onChange={(e) => setTitle(e.target.value)} maxLength={300} />
      </label>
      <label className={styles.decideLabel}>
        <span>Why</span>
        <input className="field" value={why ?? defaultWhy} placeholder="The main reason, in a sentence"
          onChange={(e) => setWhy(e.target.value)} maxLength={500} />
      </label>
      <p className={styles.ruledOut}>
        Ruled out: {listOf(matrix.options.filter((o) => o.id !== choice).map((o) => o.name))}. Notch will flag code and chats that
        bring them back.
      </p>
      {choice !== leaderId && leaderId ? <p className={styles.against}>This isn&rsquo;t the option that scored highest. That&rsquo;s fine if something the matrix doesn&rsquo;t capture matters more; say what in &ldquo;Why&rdquo;.</p> : null}
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <div>
        <button type="button" className="btn btn-primary" onClick={decide} disabled={busy || !chosen}>
          {busy ? "Recording…" : `Record decision: ${chosen?.name ?? ""}`}
        </button>
      </div>
    </section>
  );
}

/** "A", "A and B", "A, B and C" */
function listOf(names: string[]) {
  return names.length <= 1 ? names.join("") : `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
}

function DecidedNote({ projectId, view }: { projectId: string; view: ComparisonView }) {
  const others = view.comparison.options.map((o) => o.name).filter((n) => n !== view.chosen);
  return (
    <section className={styles.decidedNote} role="status">
      <h2>Decided: {view.chosen}</h2>
      <p>
        It&rsquo;s in the Ledger with {listOf(others)} ruled out. Commits, agent edits and chats that bring them back will be flagged.
      </p>
      <p className={styles.links}>
        <Link href={`/p/${projectId}/ledger`} className="btn btn-quiet">Open the Ledger</Link>
        <Link href={`/p/${projectId}/compare`} className="btn btn-quiet">Start another comparison</Link>
      </p>
    </section>
  );
}
