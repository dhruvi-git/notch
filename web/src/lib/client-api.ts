"use client";

import type { Problem } from "./types";

export class ClientApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

/**
 * Calls the API from the browser through the app's own /api/n proxy, which adds the session
 * token. An expired session reloads into the sign-in page.
 */
export async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch("/api/n" + path, init);
  if (res.status === 401) {
    window.location.href = "/sign-in?expired=1";
    throw new ClientApiError(401, "sign_in_required", "Sign in to continue.");
  }
  if (!res.ok) {
    let problem: Problem = { status: res.status };
    try {
      problem = (await res.json()) as Problem;
    } catch {
      // not JSON
    }
    throw new ClientApiError(res.status, problem.code ?? "error", problem.detail ?? "Something went wrong. Try again.");
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export function postJson<T>(path: string, body: unknown, method = "POST"): Promise<T> {
  return call<T>(path, { method, headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
}
