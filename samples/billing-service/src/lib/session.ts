/** Server-side sessions stored in Postgres, so logging out takes effect at once. */
import { cookies } from "next/headers";
import { query } from "./db";

export type Session = { id: string; customerId: string };

export async function currentSession(): Promise<Session | null> {
  const id = (await cookies()).get("sid")?.value;
  if (!id) return null;
  const rows = await query<Session>("SELECT id, customer_id AS \"customerId\" FROM sessions WHERE id = $1", [id]);
  return rows[0] ?? null;
}
