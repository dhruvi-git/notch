import { cookies } from "next/headers";
import { notFound } from "next/navigation";
import { Shell } from "@/components/Shell";
import { ApiError, api } from "@/lib/server-api";
import { THEME_COOKIE, parseTheme } from "@/lib/theme";
import type { Project, Usage, User } from "@/lib/types";

export default async function ProjectLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ projectId: string }>;
}) {
  const { projectId } = await params;
  let project: Project;
  try {
    project = await api<Project>(`/api/projects/${encodeURIComponent(projectId)}`);
  } catch (e) {
    // 404 for someone else's project as well as a missing one, so ids never leak.
    if (e instanceof ApiError && (e.status === 404 || e.status === 400)) notFound();
    throw e;
  }
  const [projects, user, usage] = await Promise.all([
    api<Project[]>("/api/projects"),
    api<User>("/api/me"),
    api<Usage>("/api/usage"),
  ]);
  const theme = parseTheme((await cookies()).get(THEME_COOKIE)?.value);
  return (
    <Shell project={project} projects={projects} user={user} usage={usage} theme={theme}>
      {children}
    </Shell>
  );
}
