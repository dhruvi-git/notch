"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { SkillPicker } from "@/components/portfolio/SkillPicker";
import { call, ClientApiError, postJson } from "@/lib/client-api";
import { LEVEL_LABEL, type Drive, type Level, type Requirement, type Role, type Shortlist, type Term } from "@/lib/portfolio-types";
import common from "@/components/portfolio/portfolio.module.css";
import styles from "../hire.module.css";

/** Segment colours for the score breakdown, one per requirement in order; they differ in lightness too. */
const SEGMENT = ["var(--accent)", "var(--ink)", "#e0a800", "#8b7aa0", "#1e9e6a", "#d4522a", "#3b82c4", "#c03a8a"];

type DraftRole = Omit<Role, "id"> & { id: string | null };

function toDraft(roles: Role[]): DraftRole[] {
  return roles.map((r) => ({ ...r, requirements: r.requirements.map((q) => ({ ...q })) }));
}

function message(err: unknown, fallback: string): string {
  return err instanceof ClientApiError ? err.message : fallback;
}

/** Edit the drive's roles on the left, see the selected role's ranked shortlist on the right. */
export function DriveWorkspace({ initial }: { initial: Drive }) {
  const router = useRouter();
  const [drive, setDrive] = useState(initial);
  const [roles, setRoles] = useState<DraftRole[]>(() => toDraft(initial.roles));
  const [selected, setSelected] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [assigning, setAssigning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [shortlist, setShortlist] = useState<Shortlist | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [loadingList, setLoadingList] = useState(false);

  const role = roles[Math.min(selected, roles.length - 1)];
  const totalSeats = roles.reduce((n, r) => n + r.seats, 0);

  const loadShortlist = useCallback(async (d: Drive, index: number) => {
    const saved = d.roles[index];
    if (!saved || saved.requirements.length === 0) {
      setShortlist(null);
      return;
    }
    setLoadingList(true);
    setListError(null);
    try {
      setShortlist(await call<Shortlist>(`/drives/${d.id}/roles/${saved.id}/shortlist?k=20`));
    } catch (err) {
      setListError(message(err, "The shortlist didn't load. Try again."));
    } finally {
      setLoadingList(false);
    }
  }, []);

  useEffect(() => {
    if (!dirty) loadShortlist(drive, selected);
  }, [drive, selected, dirty, loadShortlist]);

  function update(fn: (r: DraftRole) => DraftRole) {
    setRoles((cur) => cur.map((r, i) => (i === selected ? fn(r) : r)));
    setDirty(true);
  }

  function updateReq(skillId: number, patch: Partial<Requirement>) {
    update((r) => ({ ...r, requirements: r.requirements.map((q) => (q.skillId === skillId ? { ...q, ...patch } : q)) }));
  }

  async function save(): Promise<Drive | null> {
    setSaving(true);
    setError(null);
    try {
      const body = {
        roles: roles.map((r) => ({
          name: r.name,
          seats: r.seats,
          threshold: r.threshold,
          requirements: r.requirements.map((q) => ({ skillId: q.skillId, weight: q.weight, mustHave: q.mustHave, minLevel: q.minLevel })),
        })),
      };
      const d = await postJson<Drive>(`/drives/${drive.id}/roles`, body, "PUT");
      setDrive(d);
      setRoles(toDraft(d.roles));
      setDirty(false);
      return d;
    } catch (err) {
      setError(message(err, "The roles weren't saved. Try again."));
      return null;
    } finally {
      setSaving(false);
    }
  }

  async function assign() {
    setAssigning(true);
    setError(null);
    try {
      if (dirty && !(await save())) return;
      await call(`/drives/${drive.id}/assign`, { method: "POST" });
      router.push(`/hire/${drive.id}/assign`);
    } catch (err) {
      setError(message(err, "Seats weren't assigned. Try again."));
      setAssigning(false);
    }
  }

  return (
    <div>
      <div className={styles.driveHead}>
        <h1 className={common.title}>{drive.title}</h1>
        <div className={styles.headActions}>
          <Link href={`/hire/${drive.id}/assign`} className="btn btn-quiet">
            Last assignment
          </Link>
          <button type="button" className="btn btn-primary" onClick={assign} disabled={assigning || saving || totalSeats === 0}>
            {assigning ? "Assigning…" : `Assign all ${totalSeats} seat${totalSeats === 1 ? "" : "s"}`}
          </button>
        </div>
      </div>
      <details className={styles.jd}>
        <summary>Job description</summary>
        <p>{drive.description}</p>
      </details>

      <div role="tablist" aria-label="Roles" className={styles.tabs}>
        {roles.map((r, i) => (
          <button
            key={r.id ?? `new-${i}`}
            type="button"
            role="tab"
            aria-selected={i === selected}
            className={i === selected ? styles.tabOn : styles.tab}
            onClick={() => setSelected(i)}
          >
            {r.name || "Untitled role"} · {r.seats} seat{r.seats === 1 ? "" : "s"}
          </button>
        ))}
        <button
          type="button"
          className={styles.tabAdd}
          onClick={() => {
            setRoles((cur) => [...cur, { id: null, name: "New role", seats: 1, threshold: 50, requirements: [] }]);
            setSelected(roles.length);
            setDirty(true);
          }}
          disabled={roles.length >= 10}
        >
          + Add role
        </button>
      </div>

      {error ? (
        <p className={common.error} role="alert">
          {error}
        </p>
      ) : null}

      <div className={styles.workspace}>
        <section className={`${common.card} ${styles.editor}`} aria-labelledby="role-edit">
          <h2 id="role-edit" className={common.cardTitle}>
            What this role needs
          </h2>
          <p className={`${common.muted} ${common.small}`}>Read from your job description. Change anything, then save to update the shortlist.</p>

          <label className={styles.field}>
            <span>Role name</span>
            <input className="field" value={role.name} maxLength={80} onChange={(e) => update((r) => ({ ...r, name: e.target.value }))} />
          </label>

          <ul className={styles.reqs} aria-label="Required skills">
            {role.requirements.map((q) => (
              <li key={q.skillId} className={styles.req}>
                <span className={styles.reqName}>{q.skill}</span>
                <label className={`${styles.reqField} ${styles.reqWeight}`}>
                  <span>Weight</span>
                  <input
                    type="number"
                    className="field"
                    min={1}
                    max={10}
                    value={q.weight}
                    onChange={(e) => updateReq(q.skillId, { weight: Math.max(1, Math.min(10, Number(e.target.value) || 1)) })}
                  />
                </label>
                <label className={`${styles.reqField} ${styles.reqLevel}`}>
                  <span>Evidence at least</span>
                  <select className="field" value={q.minLevel} onChange={(e) => updateReq(q.skillId, { minLevel: e.target.value as Level })}>
                    <option value="SHOWN">Shown</option>
                    <option value="VERIFIED">Verified</option>
                  </select>
                </label>
                <label className={styles.must}>
                  <input type="checkbox" checked={q.mustHave} onChange={(e) => updateReq(q.skillId, { mustHave: e.target.checked })} />
                  <span>Must-have</span>
                </label>
                <button
                  type="button"
                  className={styles.remove}
                  aria-label={`Remove ${q.skill}`}
                  onClick={() => update((r) => ({ ...r, requirements: r.requirements.filter((x) => x.skillId !== q.skillId) }))}
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
          {role.requirements.length === 0 ? <p className={common.notice}>Add at least one skill this role needs.</p> : null}

          <SkillPicker
            label="Add a required skill"
            exclude={role.requirements.map((q) => q.skillId)}
            onPick={(s) =>
              update((r) =>
                r.requirements.length >= 15
                  ? r
                  : { ...r, requirements: [...r.requirements, { skillId: s.id, skill: s.name, weight: 2, mustHave: false, minLevel: "SHOWN" }] },
              )
            }
          />

          <div className={styles.twoCol}>
            <label className={styles.field}>
              <span>Seats</span>
              <input
                type="number"
                className="field"
                min={0}
                max={10000}
                value={role.seats}
                onChange={(e) => update((r) => ({ ...r, seats: Math.max(0, Math.min(10000, Math.floor(Number(e.target.value) || 0))) }))}
              />
            </label>
            <label className={styles.field}>
              <span>Minimum match (0–100)</span>
              <input
                type="number"
                className="field"
                min={0}
                max={100}
                value={role.threshold}
                onChange={(e) => update((r) => ({ ...r, threshold: Math.max(0, Math.min(100, Number(e.target.value) || 0)) }))}
              />
            </label>
          </div>

          <div className={styles.editorActions}>
            <button type="button" className="btn btn-primary" onClick={save} disabled={!dirty || saving}>
              {saving ? "Saving…" : dirty ? "Save changes" : "Saved"}
            </button>
            {roles.length > 1 ? (
              <button
                type="button"
                className="btn btn-quiet"
                onClick={() => {
                  setRoles((cur) => cur.filter((_, i) => i !== selected));
                  setSelected(0);
                  setDirty(true);
                }}
              >
                Remove this role
              </button>
            ) : null}
          </div>
        </section>

        <section className={styles.shortlist} aria-labelledby="short" aria-busy={loadingList}>
          <div className={styles.shortHead}>
            <h2 id="short" className={common.cardTitle}>
              Shortlist
            </h2>
            {shortlist && !dirty ? (
              <span className={`${common.muted} ${common.small}`}>
                {shortlist.poolSize} candidates searched · {shortlist.eligible} have every must-have
              </span>
            ) : null}
          </div>
          {dirty ? (
            <p className={common.notice}>Save your changes to update the shortlist.</p>
          ) : listError ? (
            <p className={common.error} role="alert">
              {listError}
            </p>
          ) : !shortlist ? (
            <p className={common.muted}>{loadingList ? "Ranking candidates…" : "Add the skills this role needs to see a shortlist."}</p>
          ) : shortlist.candidates.length === 0 ? (
            <p className={common.muted}>Nobody has every must-have yet. Try marking fewer skills as must-haves.</p>
          ) : (
            <>
              <ul className={styles.legend} aria-label="Colour key">
                {role.requirements.map((q, i) => (
                  <li key={q.skillId}>
                    <span className={styles.swatch} style={{ background: SEGMENT[i % SEGMENT.length] }} aria-hidden="true" />
                    {q.skill}
                  </li>
                ))}
              </ul>
              <ol className={styles.cands}>
                {shortlist.candidates.map((c, rank) => (
                  <li key={c.userId} className={styles.cand}>
                    <span className={styles.rank}>#{rank + 1}</span>
                    <div className={styles.candBody}>
                      <div className={styles.candName}>
                        <Link href={`/u/${c.handle}`}>@{c.handle}</Link>
                        {c.headline ? <span className={`${common.muted} ${common.small}`}>{c.headline}</span> : null}
                      </div>
                      <Breakdown terms={c.breakdown} order={role.requirements.map((q) => q.skill)} />
                      <p className={`${common.muted} ${common.small} ${styles.why}`}>{why(c.breakdown)}</p>
                    </div>
                    <div className={styles.candScore}>
                      <span className={styles.scoreBig}>{Math.round(c.score)}</span>
                      <span className={`${common.muted} ${common.small}`}>match</span>
                    </div>
                  </li>
                ))}
              </ol>
            </>
          )}
        </section>
      </div>
    </div>
  );
}

function Breakdown({ terms, order }: { terms: Term[]; order: string[] }) {
  const label = terms.map((t) => `${t.skill} ${Math.round(t.contribution)}`).join(", ");
  return (
    <span className={styles.stack} role="img" aria-label={`Score by skill: ${label}`}>
      {terms.map((t) => {
        const i = Math.max(0, order.indexOf(t.skill));
        return <span key={t.skill} style={{ width: `${Math.max(0, t.contribution)}%`, background: SEGMENT[i % SEGMENT.length] }} />;
      })}
    </span>
  );
}

/** One line on why a candidate scored as they did: their two biggest contributions, and any skill counted at half. */
function why(terms: Term[]): string {
  const top = [...terms]
    .filter((t) => t.skillScore > 0)
    .sort((a, b) => b.contribution - a.contribution)
    .slice(0, 2)
    .map((t) => `${t.skill} ${Math.round(t.skillScore)}${t.level ? ` ${LEVEL_LABEL[t.level].toLowerCase()}` : ""}`);
  const half = terms.filter((t) => t.levelFactor === 0.5).map((t) => t.skill);
  const missing = terms.filter((t) => t.skillScore === 0).map((t) => t.skill);
  const parts = [top.join(" · ")];
  if (half.length) parts.push(`${half.join(", ")} below the evidence level asked, counted at half`);
  if (missing.length) parts.push(`no ${missing.join(", ")} yet`);
  return parts.filter(Boolean).join(" · ");
}
