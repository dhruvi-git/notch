import type { Metadata } from "next";
import Link from "next/link";
import { ApiError, api } from "@/lib/server-api";
import type { CodeFileSummary } from "@/lib/types";
import { redirect } from "next/navigation";
import { FileBrowser } from "./FileBrowser";
import styles from "../explain.module.css";

export const metadata: Metadata = { title: "Every file" };

export default async function FilesPage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  let files: CodeFileSummary[];
  try {
    files = await api<CodeFileSummary[]>(`/api/projects/${projectId}/code-map/files`);
  } catch (e) {
    if (e instanceof ApiError && e.code === "no_code_map") redirect(`/p/${projectId}/explain`);
    throw e;
  }
  return (
    <div className={styles.page}>
      <p className={styles.back}>
        <Link href={`/p/${projectId}/explain`}>Back to the overview</Link>
      </p>
      <h1>Every file</h1>
      <FileBrowser projectId={projectId} files={files} />
    </div>
  );
}
