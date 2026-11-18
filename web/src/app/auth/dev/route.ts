import { NextRequest, NextResponse } from "next/server";
import { API_URL, SESSION_COOKIE, sessionCookieOptions } from "@/lib/session";
import type { Session } from "@/lib/types";

/** Local development sign-in, only available when the API runs with the local profile. */
export async function POST(req: NextRequest) {
  const form = await req.formData();
  const handle = String(form.get("handle") ?? "dev").trim() || "dev";
  const signIn = new URL("/sign-in", req.nextUrl.origin);
  try {
    const res = await fetch(`${API_URL}/auth/dev-login`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ handle }),
      cache: "no-store",
    });
    if (!res.ok) {
      signIn.searchParams.set("error", res.status === 404 ? "dev_login_off" : "invalid_handle");
      return NextResponse.redirect(signIn, 303);
    }
    const session = (await res.json()) as Session;
    const out = NextResponse.redirect(new URL("/start", req.nextUrl.origin), 303);
    out.cookies.set(SESSION_COOKIE, session.token, sessionCookieOptions(session.expiresAt));
    return out;
  } catch {
    signIn.searchParams.set("error", "api_unreachable");
    return NextResponse.redirect(signIn, 303);
  }
}
