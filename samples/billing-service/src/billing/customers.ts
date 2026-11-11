import { query } from "../lib/db";

export type Customer = { id: string; name: string; email: string };

export async function customer(id: string): Promise<Customer | null> {
  const rows = await query<Customer>("SELECT id, name, email FROM customers WHERE id = $1", [id]);
  return rows[0] ?? null;
}
