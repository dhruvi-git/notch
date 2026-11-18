import type { Metadata } from "next";
import { sessionToken } from "@/lib/server-api";
import { Landing } from "./_landing/Landing";

export const metadata: Metadata = {
  title: { absolute: "Notch: proof-of-work portfolios for tech freshers" },
};

export default async function Home() {
  // Everyone lands here first; signed-in people get a way straight to their profile.
  return <Landing signedIn={Boolean(await sessionToken())} />;
}
