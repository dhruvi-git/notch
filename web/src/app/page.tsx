import type { Metadata } from "next";
import { sessionToken } from "@/lib/server-api";
import { Landing } from "./_landing/Landing";

export const metadata: Metadata = {
  title: { absolute: "Notch: understand your code, and remember why it's built that way" },
};

export default async function Home() {
  // Everyone lands here first; signed-in people get a way straight into their projects.
  return <Landing signedIn={Boolean(await sessionToken())} />;
}
