/** Creating and reading invoices, joined to customers and line items in one transaction. */
import { pool, query } from "../lib/db";
import { subtotal, withTax, type Line } from "./pricing";
import { customer } from "./customers";

export type Invoice = { id: string; customerId: string; totalCents: number; status: "open" | "paid" };

export async function createInvoice(customerId: string, lines: Line[]): Promise<Invoice> {
  if (!(await customer(customerId))) throw new Error("Unknown customer");
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const total = withTax(subtotal(lines));
    const { rows } = await client.query(
      "INSERT INTO invoices (customer_id, total_cents, status) VALUES ($1, $2, 'open') RETURNING id",
      [customerId, total]);
    for (const l of lines) {
      await client.query("INSERT INTO line_items (invoice_id, quantity, unit_cents) VALUES ($1, $2, $3)",
        [rows[0].id, l.quantity, l.unitCents]);
    }
    await client.query("COMMIT");
    return { id: rows[0].id, customerId, totalCents: total, status: "open" };
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}

export async function invoice(id: string): Promise<Invoice | null> {
  const rows = await query<Invoice>("SELECT * FROM invoices WHERE id = $1", [id]);
  return rows[0] ?? null;
}

export async function markPaid(id: string): Promise<void> {
  await query("UPDATE invoices SET status = 'paid' WHERE id = $1", [id]);
}
