"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { call, ClientApiError } from "@/lib/client-api";
import type { Assignment } from "@/lib/portfolio-types";
import common from "@/components/portfolio/portfolio.module.css";
import styles from "../../hire.module.css";

/** Run the assignment (again), and download the current one as CSV. */
export function AssignmentActions({ driveId, result }: { driveId: string; result: Assignment | null }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      await call(`/drives/${driveId}/assign`, { method: "POST" });
      router.refresh();
    } catch (err) {
      setError(err instanceof ClientApiError ? err.message : "Seats weren't assigned. Try again.");
    } finally {
      setBusy(false);
    }
  }

  function exportCsv() {
    if (!result) return;
    const rows = [["role", "handle", "match score", "profile"]];
    for (const r of result.roles) {
      for (const p of r.placements) rows.push([r.name, p.handle, String(Math.round(p.score)), `${window.location.origin}/u/${p.handle}`]);
    }
    const csv = rows.map((row) => row.map((v) => `"${v.replaceAll('"', '""')}"`).join(",")).join("\n");
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    link.download = "notch-assignment.csv";
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  return (
    <div>
      <div className={styles.editorActions} style={{ marginTop: "1.75rem" }}>
        {result ? (
          <button type="button" className="btn btn-primary" onClick={exportCsv}>
            Export to CSV
          </button>
        ) : null}
        <button type="button" className={result ? "btn btn-quiet" : "btn btn-primary"} onClick={run} disabled={busy}>
          {busy ? "Assigning…" : result ? "Run again" : "Assign seats"}
        </button>
      </div>
      {error ? (
        <p className={common.error} role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
