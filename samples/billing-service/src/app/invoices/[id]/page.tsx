import { invoice } from "@/billing/invoices";
import { formatCents } from "@/billing/pricing";
import { track } from "@/events/track";

export default async function InvoicePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const inv = await invoice(id);
  await track("invoice_viewed", { id });
  return inv ? <h1>Invoice {inv.id}: {formatCents(inv.totalCents)}</h1> : <p>Not found.</p>;
}
