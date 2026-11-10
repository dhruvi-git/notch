"use client";

import { useEffect, useMemo, useState } from "react";
import { call, ClientApiError, postJson } from "@/lib/client-api";
import { shortDate } from "@/lib/format";
import type { Memory } from "@/lib/types";
import styles from "./ledger.module.css";

type Tab = "decisions" | "tasks" | "fixes" | "replaced";

const TABS: { id: Tab; label: string }[] = [
  { id: "decisions", label: "Decisions" },
  { id: "tasks", label: "Tasks" },
  { id: "fixes", label: "Fixes" },
  { id: "replaced", label: "Replaced" },
];

function inTab(m: Memory, tab: Tab): boolean {
  switch (tab) {
    case "decisions":
      return m.kind === "DECISION" && m.status === "ACTIVE";
    case "tasks":
      return m.kind === "TASK";
    case "fixes":
      return m.kind === "FIX";
    case "replaced":
      return m.kind === "DECISION" && m.status === "SUPERSEDED";
  }
}

export function Ledger({ projectId, initial }: { projectId: string; initial: Memory[] }) {
  const [memories, setMemories] = useState<Memory[]>(initial);
  const [tab, setTab] = useState<Tab>("decisions");
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const counts = useMemo(() => {
    const c: Record<Tab, number> = { decisions: 0, tasks: 0, fixes: 0, replaced: 0 };
    for (const m of memories) for (const t of TABS) if (inTab(m, t.id)) c[t.id] += t.id === "tasks" && m.status === "DONE" ? 0 : 1;
    return c;
  }, [memories]);

  const visible = memories.filter((m) => inTab(m, tab));
  const byId = useMemo(() => new Map(memories.map((m) => [m.id, m])), [memories]);

  function upsert(...changed: Memory[]) {
    setMemories((list) => {
      const next = [...list];
      for (const c of changed) {
        const i = next.findIndex((m) => m.id === c.id);
        if (i >= 0) next[i] = c;
        else next.unshift(c);
      }
      return next;
    });
  }

  async function toggleTask(m: Memory) {
    const status = m.status === "DONE" ? "ACTIVE" : "DONE";
    upsert({ ...m, status });  // feels instant; rolled back below if the server disagrees
    try {
      upsert(await postJson<Memory>(`/memories/${m.id}`, { status }, "PATCH"));
    } catch (err) {
      upsert(m);
      setError(err instanceof ClientApiError ? err.message : "That change wasn't saved. Try again.");
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.head}>
        <div>
          <h1>Ledger</h1>
          <p className={styles.intro}>
            What this project decided, what&rsquo;s still to do, and how past errors were fixed. Each entry links back to
            the conversation it came from.
          </p>
        </div>
        <button type="button" className="btn btn-quiet" onClick={() => setAdding((a) => !a)} aria-expanded={adding}>
          {adding ? "Close" : tab === "tasks" ? "Add a task" : "Record a decision"}
        </button>
      </div>

      {adding ? (
        <AddForm
          projectId={projectId}
          kind={tab === "tasks" ? "TASK" : "DECISION"}
          onAdded={(m) => {
            upsert(m);
            setAdding(false);
            setTab(m.kind === "TASK" ? "tasks" : "decisions");
          }}
        />
      ) : null}

      <div className={styles.tabs} role="tablist" aria-label="Ledger sections">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            id={`tab-${t.id}`}
            aria-selected={tab === t.id}
            aria-controls="ledger-panel"
            className={styles.tab}
            onClick={() => setTab(t.id)}
          >
            {t.label}
            <span className={styles.count}>{counts[t.id]}</span>
          </button>
        ))}
      </div>

      {error ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : null}

      <div id="ledger-panel" role="tabpanel" aria-labelledby={`tab-${tab}`}>
        {visible.length === 0 ? (
          <p className={styles.empty}>{EMPTY[tab]}</p>
        ) : (
          <ul className={styles.list}>
            {[...visible]
              .sort((a, b) => (tab === "tasks" ? Number(a.status === "DONE") - Number(b.status === "DONE") : 0))
              .map((m) =>
                m.kind === "TASK" ? (
                  <TaskItem key={m.id} task={m} onToggle={() => toggleTask(m)} />
                ) : m.kind === "FIX" ? (
                  <FixItem key={m.id} fix={m} />
                ) : (
                  <DecisionItem
                    key={m.id}
                    decision={m}
                    replacedBy={m.supersededBy ? byId.get(m.supersededBy) : undefined}
                    others={memories.filter((x) => x.kind === "DECISION" && x.status === "ACTIVE" && x.id !== m.id)}
                    onReplaced={(original, replacement) => upsert(original, replacement)}
                  />
                ),
              )}
          </ul>
        )}
      </div>
    </div>
  );
}

const EMPTY: Record<Tab, string> = {
  decisions: "No decisions yet. They're found in imported chats, or you can record one yourself.",
  tasks: "No open tasks. Checklists and phrases like \u201cnext step\u201d in your chats show up here.",
  fixes: "No fixes yet. When a chat names an error and how it was solved, it's kept here.",
  replaced: "No decision has been replaced. When plans change, replace a decision to keep its history.",
};

function Source({ m }: { m: Memory }) {
  return (
    <p className={styles.source}>
      {m.conversationTitle ? <>From &ldquo;{m.conversationTitle}&rdquo;, </>
        : m.body === "Decided in Compare." ? <>Decided in Compare, </> : <>Recorded by hand, </>}
      {shortDate(m.createdAt)}
    </p>
  );
}

function DecisionItem({
  decision,
  replacedBy,
  others,
  onReplaced,
}: {
  decision: Memory;
  replacedBy?: Memory;
  others: Memory[];
  onReplaced: (original: Memory, replacement: Memory) => void;
}) {
  const [panel, setPanel] = useState<"none" | "replace" | "history">("none");
  const replaced = decision.status === "SUPERSEDED";
  const why = decision.rationale && !decision.title.toLowerCase().includes(decision.rationale.toLowerCase()) ? decision.rationale : null;
  return (
    <li className={styles.item} data-replaced={replaced}>
      <p className={styles.decision}>{decision.title}</p>
      {why || decision.rejectedOptions.length ? (
        <dl className={styles.facts}>
          {why ? (
            <div>
              <dt>Why</dt>
              <dd>{why}</dd>
            </div>
          ) : null}
          {decision.rejectedOptions.length ? (
            <div>
              <dt>Ruled out</dt>
              <dd>{decision.rejectedOptions.join(", ")}</dd>
            </div>
          ) : null}
        </dl>
      ) : null}
      {replaced && replacedBy ? <p className={styles.replacedBy}>Replaced by: {replacedBy.title}</p> : null}
      <div className={styles.itemFoot}>
        <Source m={decision} />
        <div className={styles.actions}>
          {!replaced ? (
            <button type="button" className={styles.link} aria-expanded={panel === "replace"}
              onClick={() => setPanel(panel === "replace" ? "none" : "replace")}>
              Replace
            </button>
          ) : null}
          <button type="button" className={styles.link} aria-expanded={panel === "history"}
            onClick={() => setPanel(panel === "history" ? "none" : "history")}>
            History
          </button>
        </div>
      </div>
      {panel === "replace" ? (
        <ReplaceForm decision={decision} others={others} onDone={(o, r) => { onReplaced(o, r); setPanel("none"); }} />
      ) : null}
      {panel === "history" ? <History id={decision.id} /> : null}
    </li>
  );
}

function ReplaceForm({
  decision,
  others,
  onDone,
}: {
  decision: Memory;
  others: Memory[];
  onDone: (original: Memory, replacement: Memory) => void;
}) {
  const [mode, setMode] = useState<"existing" | "new">(others.length ? "existing" : "new");
  const [replacementId, setReplacementId] = useState("");
  const [title, setTitle] = useState("");
  const [rationale, setRationale] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = mode === "existing" ? { replacementId } : { title: title.trim(), rationale: rationale.trim() || null };
      const replacement = await postJson<Memory>(`/memories/${decision.id}/supersede`, body);
      onDone({ ...decision, status: "SUPERSEDED", supersededBy: replacement.id }, replacement);
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The decision wasn't replaced. Try again.");
      setBusy(false);
    }
  }

  return (
    <form className={styles.panel} onSubmit={submit}>
      <fieldset className={styles.modes}>
        <legend>Replace this decision with</legend>
        {others.length ? (
          <label>
            <input type="radio" name={`mode-${decision.id}`} checked={mode === "existing"} onChange={() => setMode("existing")} />
            A decision already in the ledger
          </label>
        ) : null}
        <label>
          <input type="radio" name={`mode-${decision.id}`} checked={mode === "new"} onChange={() => setMode("new")} />
          A new decision
        </label>
      </fieldset>
      {mode === "existing" ? (
        <select className="field" value={replacementId} onChange={(e) => setReplacementId(e.target.value)} aria-label="Replacement decision" required>
          <option value="" disabled>
            Choose the decision that replaces it
          </option>
          {others.map((o) => (
            <option key={o.id} value={o.id}>
              {o.title.length > 110 ? o.title.slice(0, 109) + "…" : o.title}
            </option>
          ))}
        </select>
      ) : (
        <>
          <textarea className="field" rows={2} value={title} onChange={(e) => setTitle(e.target.value)}
            placeholder="We'll use JWT for API tokens" aria-label="New decision" maxLength={300} required />
          <input className="field" value={rationale} onChange={(e) => setRationale(e.target.value)}
            placeholder="Why (optional)" aria-label="Why" maxLength={500} />
        </>
      )}
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <div>
        <button type="submit" className="btn btn-primary"
          disabled={busy || (mode === "new" ? !title.trim() : !replacementId)}>
          {busy ? "Replacing…" : "Replace decision"}
        </button>
      </div>
      <p className={styles.panelNote}>The current decision stays in the ledger&rsquo;s history, marked as replaced.</p>
    </form>
  );
}

function History({ id }: { id: string }) {
  const [chain, setChain] = useState<Memory[] | null>(null);
  const [error, setError] = useState(false);
  useEffect(() => {
    let live = true;
    call<Memory[]>(`/memories/${id}/history`)
      .then((c) => live && setChain(c))
      .catch(() => live && setError(true));
    return () => {
      live = false;
    };
  }, [id]);
  if (error) return <p className={styles.panelNote}>The history didn&rsquo;t load. Try again.</p>;
  if (!chain) return <p className={styles.panelNote}>Loading history…</p>;
  if (chain.length === 1) return <p className={styles.panelNote}>This decision hasn&rsquo;t been replaced, and it didn&rsquo;t replace an earlier one.</p>;
  return (
    <ol className={styles.history} aria-label="Decision history, oldest first">
      {chain.map((m) => (
        <li key={m.id} data-current={m.id === id} data-status={m.status}>
          <span className={styles.historyDate}>{shortDate(m.createdAt)}</span>
          <span>{m.title}</span>
          <span className={styles.historyStatus}>{m.status === "ACTIVE" ? "In force" : "Replaced"}</span>
        </li>
      ))}
    </ol>
  );
}

function TaskItem({ task, onToggle }: { task: Memory; onToggle: () => void }) {
  const done = task.status === "DONE";
  return (
    <li className={styles.item} data-done={done}>
      <label className={styles.task}>
        <input type="checkbox" checked={done} onChange={onToggle} />
        <span className={styles.taskTitle}>{task.title}</span>
      </label>
      <div className={styles.taskSource}>
        <Source m={task} />
      </div>
    </li>
  );
}

function FixItem({ fix }: { fix: Memory }) {
  const error = fix.title.replace(/^Fix:\s*/, "");
  return (
    <li className={styles.item}>
      <p className={styles.fixError}>
        <code>{error}</code>
      </p>
      {fix.body ? <p className={styles.fixBody}>{fix.body}</p> : null}
      <Source m={fix} />
    </li>
  );
}

function AddForm({ projectId, kind, onAdded }: { projectId: string; kind: "DECISION" | "TASK"; onAdded: (m: Memory) => void }) {
  const [title, setTitle] = useState("");
  const [rationale, setRationale] = useState("");
  const [ruledOut, setRuledOut] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const decision = kind === "DECISION";

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const m = await postJson<Memory>(`/projects/${projectId}/memories`, {
        kind,
        title: title.trim(),
        rationale: decision ? rationale.trim() || null : null,
        rejectedOptions: decision ? ruledOut.split(",").map((s) => s.trim()).filter(Boolean) : [],
      });
      onAdded(m);
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "It wasn't saved. Try again.");
      setBusy(false);
    }
  }

  return (
    <form className={`${styles.panel} ${styles.addForm}`} onSubmit={submit}>
      <label className={styles.fieldLabel} htmlFor="add-title">
        {decision ? "Decision" : "Task"}
      </label>
      <textarea id="add-title" className="field" rows={2} value={title} onChange={(e) => setTitle(e.target.value)}
        placeholder={decision ? "We'll use Stripe webhooks for payment status" : "Add idempotency keys to invoice creation"}
        maxLength={300} required autoFocus />
      {decision ? (
        <div className={styles.addRow}>
          <div>
            <label className={styles.fieldLabel} htmlFor="add-why">Why <span className="muted">(optional)</span></label>
            <input id="add-why" className="field" value={rationale} onChange={(e) => setRationale(e.target.value)}
              placeholder="Polling would hit Stripe's rate limits" maxLength={500} />
          </div>
          <div>
            <label className={styles.fieldLabel} htmlFor="add-out">Ruled out <span className="muted">(optional)</span></label>
            <input id="add-out" className="field" value={ruledOut} onChange={(e) => setRuledOut(e.target.value)}
              placeholder="Polling, PayPal" />
          </div>
        </div>
      ) : null}
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <div>
        <button type="submit" className="btn btn-primary" disabled={busy || !title.trim()}>
          {busy ? "Saving…" : decision ? "Record decision" : "Add task"}
        </button>
      </div>
    </form>
  );
}

