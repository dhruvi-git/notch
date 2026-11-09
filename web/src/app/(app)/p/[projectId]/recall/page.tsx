import type { Metadata } from "next";
import { api } from "@/lib/server-api";
import type { ConversationSummary, Usage } from "@/lib/types";
import { Recall } from "./Recall";

export const metadata: Metadata = { title: "Recall" };

export default async function RecallPage({
  params,
  searchParams,
}: {
  params: Promise<{ projectId: string }>;
  searchParams: Promise<{ q?: string }>;
}) {
  const { projectId } = await params;
  const { q } = await searchParams;
  const [usage, conversations] = await Promise.all([
    api<Usage>("/api/usage"),
    api<ConversationSummary[]>(`/api/projects/${projectId}/conversations`),
  ]);
  return (
    <Recall
      projectId={projectId}
      initialQuery={q ?? ""}
      questionsLeft={usage.remaining.question}
      hasContent={conversations.length > 0}
    />
  );
}
