import type { Metadata } from "next";
import { Sync } from "./Sync";

export const metadata: Metadata = { title: "Sync" };

export default async function SyncPage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  return <Sync projectId={projectId} />;
}
