import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { API_URL, SESSION_COOKIE } from "./session";
import type { Problem } from "./types";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

export async function sessionToken(): Promise<string | undefined> {
  return (await cookies()).get(SESSION_COOKIE)?.value;
}

/**
 * Calls the API from a server component with the session token. An expired session sends the
 * person back to sign in; other errors are thrown with the API's own message.
 */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await sessionToken();
  const headers = new Headers(init.headers);
  if (token) headers.set("authorization", `Bearer ${token}`);
  let res: Response;
  try {
    res = await fetch(API_URL + path, { ...init, headers, cache: "no-store" });
  } catch {
    throw new ApiError(503, "api_unreachable", `Can't reach the Notch API at ${API_URL}.`);
  }
  if (res.status === 401) redirect("/sign-in?expired=1");
  if (!res.ok) throw await toApiError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

/** For public endpoints (sign-in), without a session and without redirecting. */
export async function publicApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res: Response;
  try {
    res = await fetch(API_URL + path, { ...init, cache: "no-store" });
  } catch {
    throw new ApiError(503, "api_unreachable", `Can't reach the Notch API at ${API_URL}.`);
  }
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as T;
}

export async function toApiError(res: Response): Promise<ApiError> {
  let problem: Problem = { status: res.status };
  try {
    problem = (await res.json()) as Problem;
  } catch {
    // not JSON: keep the status alone
  }
  return new ApiError(res.status, problem.code ?? "error", problem.detail ?? `The API returned ${res.status}.`);
}
