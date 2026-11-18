import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { TopBar } from "@/components/portfolio/TopBar";
import { api, ApiError } from "@/lib/server-api";
import { shortDate } from "@/lib/format";
import type { Drive } from "@/lib/portfolio-types";
import type { User } from "@/lib/types";
import common from "@/components/portfolio/portfolio.module.css";
import { DriveWorkspace } from "./DriveWorkspace";

export const metadata: Metadata = { title: "Drive" };

type Params = { driveId: string };

export default async function DrivePage({ params }: { params: Promise<Params> }) {
  const { driveId } = await params;
  let drive: Drive;
  try {
    drive = await api<Drive>(`/api/drives/${encodeURIComponent(driveId)}`);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 400)) notFound();
    throw e;
  }
  const user = await api<User>("/api/me");
  return (
    <div>
      <TopBar user={user} current="hire" />
      <main className={common.container}>
        <p className={`${common.muted} ${common.small}`}>Drive · created {shortDate(drive.createdAt)}</p>
        <DriveWorkspace initial={drive} />
      </main>
    </div>
  );
}
