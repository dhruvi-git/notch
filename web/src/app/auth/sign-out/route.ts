import { NextRequest, NextResponse } from "next/server";
import { API_URL, SESSION_COOKIE } from "@/lib/session";

/** Revokes the session on the API, then clears the cookie. */
export async function POST(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (token) {
    await fetch(`${API_URL}/api/logout`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
      cache: "no-store",
    }).catch(() => undefined);  // signing out locally still works if the API is down
  }
  const out = NextResponse.redirect(new URL("/sign-in?signed_out=1", req.nextUrl.origin), 303);
  out.cookies.delete(SESSION_COOKIE);
  return out;
}
