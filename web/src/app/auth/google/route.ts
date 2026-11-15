import { NextRequest, NextResponse } from "next/server";
import { API_URL } from "@/lib/session";

/** Starts Google sign-in: the API sends the browser to Google and back to /auth/callback. */
export function GET(req: NextRequest) {
  const callback = new URL("/auth/callback", req.nextUrl.origin).toString();
  return NextResponse.redirect(`${API_URL}/auth/google/start?redirect=${encodeURIComponent(callback)}`);
}
