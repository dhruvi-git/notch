"use client";

import { useEffect, useState } from "react";
import { call, ClientApiError } from "@/lib/client-api";
import type { ContextPack } from "@/lib/types";
import styles from "./sync.module.css";

type Target = "pack" | "agents" | "claude";

const TARGETS: { id: Target; label: string; help: string }[] = [
  { id: "pack", label: "Context pack", help: "Paste it at the start of a new chat with any assistant." },
  {
    id: "agents",
    label: "AGENTS.md",
    help: "Save it in your repository's root folder. Codex, Cursor and other coding agents read it on their own.",
  },
  { id: "claude", label: "CLAUDE.md", help: "Save it in your repository's root folder. Claude Code reads it at the start of each session." },
];

export function Sync({ projectId }: { projectId: string }) {
  const [target, setTarget] = useState<Target>("pack");
  const [budget, setBudget] = useState(1500);
  const [text, setText] = useState<string | null>(null);
  const [tokens, setTokens] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let live = true;
    setCopied(false);
    const t = setTimeout(async () => {
      try {
        if (target === "pack") {
          const p = await call<ContextPack>(`/projects/${projectId}/context-pack?budget=${budget}`);
          if (live) {
            setText(p.markdown);
            setTokens(p.estimatedTokens);
          }
        } else {
          const res = await fetch(`/api/n/projects/${projectId}/export/${target}-md`);
          if (!res.ok) throw new ClientApiError(res.status, "export_failed", "The file couldn't be generated. Try again.");
          const md = await res.text();
          if (live) {
            setText(md);
            setTokens(Math.ceil(md.length / 4));
          }
        }
        if (live) setError(null);
      } catch (err) {
        if (live) setError(err instanceof ClientApiError ? err.message : "The preview didn't load. Try again.");
      }
    }, 150);
    return () => {
      live = false;
      clearTimeout(t);
    };
  }, [projectId, target, budget]);

  async function copy() {
    if (!text) return;
    await navigator.clipboard.writeText(text);
    setCopied(true);
  }

  const current = TARGETS.find((t) => t.id === target)!;
  return (
    <div className={styles.page}>
      <h1>Sync</h1>
      <p className={styles.intro}>
        Give your coding assistant this project&rsquo;s decisions, so it follows them instead of starting from zero. Each
        version is written fresh from the Ledger: decisions first, then open tasks, where you stopped, and known fixes.
      </p>

      <div className={styles.tabs} role="tablist" aria-label="Format">
        {TARGETS.map((t) => (
          <button key={t.id} type="button" role="tab" aria-selected={target === t.id} className={styles.tab}
            onClick={() => setTarget(t.id)}>
            {t.label}
          </button>
        ))}
      </div>

      <div className={styles.toolbar}>
        <p className={styles.help}>{current.help}</p>
        {target === "pack" ? (
          <label className={styles.budget}>
            <span>Length</span>
            <input type="range" min={300} max={4000} step={100} value={budget} onChange={(e) => setBudget(Number(e.target.value))} />
            <span className={styles.budgetValue}>{budget.toLocaleString("en")} tokens</span>
          </label>
        ) : null}
      </div>

      {error ? <p className={styles.error} role="alert">{error}</p> : null}

      <div className={styles.preview}>
        <div className={styles.previewBar}>
          <span className={styles.size}>{tokens !== null ? `About ${tokens.toLocaleString("en")} tokens` : "Loading…"}</span>
          <div className={styles.previewActions}>
            <button type="button" className="btn btn-quiet" onClick={copy} disabled={!text}>
              {copied ? "Copied" : "Copy"}
            </button>
            {target !== "pack" ? (
              <a className="btn btn-primary" href={`/api/n/projects/${projectId}/export/${target}-md`} download>
                Download {current.label}
              </a>
            ) : null}
          </div>
        </div>
        <pre className={styles.pre} aria-label={`${current.label} preview`}>{text ?? ""}</pre>
      </div>
    </div>
  );
}
