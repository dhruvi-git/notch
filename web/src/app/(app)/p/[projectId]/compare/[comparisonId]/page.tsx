import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ApiError, api } from "@/lib/server-api";
import type { ComparisonView } from "@/lib/types";
import { Workbench } from "./Workbench";

export const metadata: Metadata = { title: "Compare" };

export default async function ComparisonPage({ params }: { params: Promise<{ projectId: string; comparisonId: string }> }) {
  const { projectId, comparisonId } = await params;
  let view: ComparisonView;
  try {
    view = await api<ComparisonView>(`/api/comparisons/${encodeURIComponent(comparisonId)}`);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 400)) notFound();
    throw e;
  }
  return <Workbench projectId={projectId} initial={view} />;
}
