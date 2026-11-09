import { redirect } from "next/navigation";
import { sessionToken } from "@/lib/server-api";

/** Every page in this group needs a signed-in session. */
export default async function AppLayout({ children }: { children: React.ReactNode }) {
  if (!(await sessionToken())) redirect("/sign-in");
  return children;
}
