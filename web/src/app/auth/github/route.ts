import { NextRequest, NextResponse } from "next/server";
import { API_URL } from "@/lib/session";

/** Starts GitHub sign-in: the API sends the browser to GitHub and back to /auth/callback. */
export function GET(req: NextRequest) {
  const callback = new URL("/auth/callback", req.nextUrl.origin).toString();
  return NextResponse.redirect(`${API_URL}/auth/github/start?redirect=${encodeURIComponent(callback)}`);
}
