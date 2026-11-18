import { NextRequest, NextResponse } from "next/server";
import { API_URL, SESSION_COOKIE, sessionCookieOptions } from "@/lib/session";
import type { Session } from "@/lib/types";

/**
 * GitHub sign-in lands here with a one-time code. The code is traded for a session token on
 * the server, and the token is stored in an httpOnly cookie that page scripts cannot read.
 */
export async function GET(req: NextRequest) {
  const code = req.nextUrl.searchParams.get("code");
  const error = req.nextUrl.searchParams.get("error");
  const signIn = new URL("/sign-in", req.nextUrl.origin);
  if (error || !code) {
    signIn.searchParams.set("error", error ?? "missing_code");
    return NextResponse.redirect(signIn);
  }
  try {
    const res = await fetch(`${API_URL}/auth/exchange`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code }),
      cache: "no-store",
    });
    if (!res.ok) {
      signIn.searchParams.set("error", "code_expired");
      return NextResponse.redirect(signIn);
    }
    const session = (await res.json()) as Session;
    const out = NextResponse.redirect(new URL("/start", req.nextUrl.origin));
    out.cookies.set(SESSION_COOKIE, session.token, sessionCookieOptions(session.expiresAt));
    return out;
  } catch {
    signIn.searchParams.set("error", "api_unreachable");
    return NextResponse.redirect(signIn);
  }
}
