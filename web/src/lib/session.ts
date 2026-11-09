export const SESSION_COOKIE = "notch_session";

/** The API's address. Read on the server only; the browser goes through /api/n. */
export const API_URL = (process.env.NOTCH_API_URL ?? "http://localhost:8080").replace(/\/$/, "");

export function sessionCookieOptions(expiresAt: string) {
  return {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax" as const,
    path: "/",
    expires: new Date(expiresAt),
  };
}
