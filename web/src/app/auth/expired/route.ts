import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/session";

/**
 * The API no longer accepts the session (it expired, or the server's data was reset). Clear the
 * cookie before showing sign-in: the sign-in page sends anyone with a cookie on to their
 * projects, so a stale cookie would bounce between the two pages forever.
 */
export function GET(req: NextRequest) {
  const out = NextResponse.redirect(new URL("/sign-in?expired=1", req.nextUrl.origin));
  out.cookies.delete(SESSION_COOKIE);
  return out;
}
