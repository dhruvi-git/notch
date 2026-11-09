import { redirect } from "next/navigation";
import { sessionToken } from "@/lib/server-api";

export default async function Home() {
  redirect((await sessionToken()) ? "/projects" : "/sign-in");
}
