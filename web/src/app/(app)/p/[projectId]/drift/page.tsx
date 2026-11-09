import type { Metadata } from "next";
import { api } from "@/lib/server-api";
import type { DriftAlert, Memory } from "@/lib/types";
import { Drift } from "./Drift";

export const metadata: Metadata = { title: "Drift" };

export default async function DriftPage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  const [alerts, decisions] = await Promise.all([
    api<DriftAlert[]>(`/api/projects/${projectId}/drift`),
    api<Memory[]>(`/api/projects/${projectId}/memories?kind=decision&limit=500`),
  ]);
  const titles = Object.fromEntries(decisions.map((d) => [d.id, d.title]));
  return <Drift projectId={projectId} initial={alerts} titles={titles} />;
}
