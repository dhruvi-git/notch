import type { Metadata } from "next";
import { TopBar } from "@/components/portfolio/TopBar";
import { api } from "@/lib/server-api";
import type { ProfileView } from "@/lib/portfolio-types";
import type { MyRead } from "@/lib/read-types";
import type { User } from "@/lib/types";
import { You } from "./You";

export const metadata: Metadata = { title: "You" };

export default async function Start() {
  const [user, profile, mine, prompt] = await Promise.all([
    api<User>("/api/me"),
    api<ProfileView>("/api/portfolio/me"),
    api<MyRead>("/api/insights/me"),
    api<{ prompt: string }>("/api/portfolio/memory-prompt"),
  ]);
  return (
    <div>
      <TopBar user={user} current="profile" tone="dark" profileHandle={profile.hasProfile && profile.isPublic ? profile.handle : null} />
      <You user={user} profile={profile} mine={mine} prompt={prompt.prompt} />
    </div>
  );
}
