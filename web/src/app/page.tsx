import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { sessionToken } from "@/lib/server-api";
import { Landing } from "./_landing/Landing";

export const metadata: Metadata = {
  title: { absolute: "Notch: the decision record for AI-assisted projects" },
};

export default async function Home() {
  if (await sessionToken()) redirect("/projects");
  return <Landing />;
}
