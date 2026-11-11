"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { PathLabel } from "@/components/PathLabel";
import { fileHref, ROLE_LABEL } from "@/lib/code";
import { plural } from "@/lib/format";
import type { CodeFileSummary, CodeRole } from "@/lib/types";
import styles from "../explain.module.css";

const GROUPS: { label: string; roles: CodeRole[] }[] = [
  { label: "All", roles: [] },
  { label: "Pages and APIs", roles: ["PAGE", "LAYOUT", "API_ROUTE", "CONTROLLER", "ENTRY"] },
  { label: "Code", roles: ["CODE", "COMPONENT", "SERVICE", "SCRIPT"] },
  { label: "Tests", roles: ["TEST"] },
  { label: "Config and data", roles: ["CONFIG", "MIGRATION", "DATA", "STYLE", "DOC"] },
];

export function FileBrowser({ projectId, files }: { projectId: string; files: CodeFileSummary[] }) {
  const [query, setQuery] = useState("");
  const [group, setGroup] = useState(0);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    const roles = GROUPS[group].roles;
    return files.filter((f) => (roles.length === 0 || roles.includes(f.role))
      && (!q || f.path.toLowerCase().includes(q) || f.summary.toLowerCase().includes(q)));
  }, [files, query, group]);
  const byModule = useMemo(() => {
    const m = new Map<string, CodeFileSummary[]>();
    for (const f of shown) m.set(f.module, [...(m.get(f.module) ?? []), f]);
    return [...m.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [shown]);

  return (
    <>
      <div className={styles.browserBar}>
        <input type="search" className="field" placeholder="Filter by path or description" value={query}
          onChange={(e) => setQuery(e.target.value)} aria-label="Filter files" />
        <div className={styles.groupChips} role="group" aria-label="Kind of file">
          {GROUPS.map((g, i) => (
            <button key={g.label} type="button" className={styles.groupChip} aria-pressed={group === i} onClick={() => setGroup(i)}>
              {g.label}
            </button>
          ))}
        </div>
      </div>
      <p className={styles.browserCount} aria-live="polite">{plural(shown.length, "file")}</p>
      {byModule.map(([module, list]) => (
        <section key={module} className={styles.moduleGroup}>
          <h2><code>{module}</code></h2>
          <ul>
            {list.map((f) => (
              <li key={f.path}>
                <Link href={fileHref(projectId, f.path)} className={styles.fileRow}>
                  <span className={styles.fileRowMain}>
                    <PathLabel path={f.path} />
                    {f.summary ? <span className={styles.fileRowSummary}>{f.summary}</span> : null}
                  </span>
                  <span className={styles.fileRowMeta}>
                    {f.conflict ? <span className="chip chip-warn">Goes against a decision</span> : null}
                    <span className="chip">{ROLE_LABEL[f.role]}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </>
  );
}
