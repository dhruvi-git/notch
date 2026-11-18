import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { api, ApiError } from "@/lib/server-api";
import type { CheckView } from "@/lib/portfolio-types";
import { Interview } from "./Interview";

export const metadata: Metadata = { title: "Ownership Check" };

export default async function CheckPage({ params }: { params: Promise<{ checkId: string }> }) {
  const { checkId } = await params;
  let check: CheckView;
  try {
    check = await api<CheckView>(`/api/ownership/checks/${encodeURIComponent(checkId)}`);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 400)) notFound();
    throw e;
  }
  return <Interview initial={check} />;
}
