import type { Metadata } from "next";
import { TopBar } from "@/components/portfolio/TopBar";
import { api } from "@/lib/server-api";
import type { ProfileView } from "@/lib/portfolio-types";
import type { User } from "@/lib/types";
import common from "@/components/portfolio/portfolio.module.css";
import { Onboarding } from "./Onboarding";

export const metadata: Metadata = { title: "My profile" };

export default async function Start() {
  const [user, profile] = await Promise.all([api<User>("/api/me"), api<ProfileView>("/api/portfolio/me")]);
  return (
    <div>
      <TopBar user={user} current="profile" />
      <main className={`${common.container} ${common.narrow}`}>
        <h1 className={common.title}>{profile.hasProfile ? "Your profile" : "Build your profile"}</h1>
        <p className={common.lede}>Connect your work, approve what Notch found, and publish one link. You approve everything before it shows.</p>
        <Onboarding user={user} profile={profile} />
      </main>
    </div>
  );
}
