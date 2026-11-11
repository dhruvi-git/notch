import { query } from "@/lib/db";
import { currentSession } from "@/lib/session";
import { InvoiceTable } from "@/components/InvoiceTable";
import type { Invoice } from "@/billing/invoices";

export default async function Dashboard() {
  const session = await currentSession();
  if (!session) return <p>Sign in to see your invoices.</p>;
  const invoices = await query<Invoice>("SELECT * FROM invoices WHERE customer_id = $1", [session.customerId]);
  return <InvoiceTable invoices={invoices} />;
}
