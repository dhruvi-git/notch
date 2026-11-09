import type { Metadata } from "next";
import { api } from "@/lib/server-api";
import type { Memory } from "@/lib/types";
import { Ledger } from "./Ledger";

export const metadata: Metadata = { title: "Ledger" };

export default async function LedgerPage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  const memories = await api<Memory[]>(`/api/projects/${projectId}/memories?limit=500`);
  return <Ledger projectId={projectId} initial={memories} />;
}
