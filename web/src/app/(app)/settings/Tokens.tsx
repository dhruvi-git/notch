"use client";

import { useState } from "react";
import { call, ClientApiError, postJson } from "@/lib/client-api";
import { relativeTime, shortDate } from "@/lib/format";
import type { NewToken, TokenInfo } from "@/lib/types";
import styles from "./settings.module.css";

export function Tokens({ initial }: { initial: TokenInfo[] }) {
  const [tokens, setTokens] = useState(initial);
  const [name, setName] = useState("");
  const [created, setCreated] = useState<NewToken | null>(null);
  const [copied, setCopied] = useState(false);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) {
      setError("Name the token, for example after the device it's for.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const t = await postJson<NewToken>("/tokens", { name: name.trim() });
      setCreated(t);
      setCopied(false);
      setTokens((list) => [{ id: t.id, name: t.name, prefix: t.prefix, createdAt: t.createdAt, lastUsedAt: null }, ...list]);
      setName("");
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The token wasn't created. Try again.");
    } finally {
      setBusy(false);
    }
  }

  async function revoke(id: string) {
    setBusy(true);
    setError(null);
    try {
      await call(`/tokens/${id}`, { method: "DELETE" });
      setTokens((list) => list.filter((t) => t.id !== id));
      if (created?.id === id) setCreated(null);
      setConfirming(null);
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "The token wasn't revoked. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.tokens}>
      {created ? (
        <div className={styles.reveal} role="status">
          <p className={styles.revealTitle}>Copy this token now. It won&rsquo;t be shown again.</p>
          <div className={styles.revealRow}>
            <code className={styles.secret}>{created.token}</code>
            <button type="button" className="btn btn-primary" onClick={async () => {
              await navigator.clipboard.writeText(created.token);
              setCopied(true);
            }}>
              {copied ? "Copied" : "Copy token"}
            </button>
          </div>
          <p className={styles.revealHelp}>Paste it into the extension&rsquo;s settings. Anyone with this token can use this account, so keep it private.</p>
        </div>
      ) : null}

      <form className={styles.create} onSubmit={create} noValidate>
        <label htmlFor="token-name" className={styles.label}>New token</label>
        <div className={styles.createRow}>
          <input id="token-name" className="field" value={name} onChange={(e) => setName(e.target.value)}
            placeholder="Work laptop, VS Code" maxLength={60} />
          <button type="submit" className="btn btn-quiet" disabled={busy}>Create token</button>
        </div>
      </form>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}

      {tokens.length === 0 ? (
        <p className="muted">No tokens yet.</p>
      ) : (
        <ul className={styles.list}>
          {tokens.map((t) => (
            <li key={t.id} className={styles.row}>
              <div>
                <p className={styles.tokenName}>{t.name}</p>
                <p className={styles.tokenMeta}>
                  <code>{t.prefix}…</code> created {shortDate(t.createdAt)},{" "}
                  {t.lastUsedAt ? `last used ${relativeTime(t.lastUsedAt).toLowerCase()}` : "not used yet"}
                </p>
              </div>
              {confirming === t.id ? (
                <div className={styles.confirm}>
                  <span>Revoke &ldquo;{t.name}&rdquo;?</span>
                  <button type="button" className={styles.danger} onClick={() => revoke(t.id)} disabled={busy}>Revoke</button>
                  <button type="button" className={styles.linkButton} onClick={() => setConfirming(null)}>Keep</button>
                </div>
              ) : (
                <button type="button" className={styles.linkButton} onClick={() => setConfirming(t.id)}>Revoke</button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
