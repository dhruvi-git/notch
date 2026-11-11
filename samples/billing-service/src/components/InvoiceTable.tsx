import { formatCents } from "@/billing/pricing";
import type { Invoice } from "@/billing/invoices";

export function InvoiceTable({ invoices }: { invoices: Invoice[] }) {
  return (
    <table>
      <tbody>
        {invoices.map((i) => (
          <tr key={i.id}><td>{i.id}</td><td>{formatCents(i.totalCents)}</td><td>{i.status}</td></tr>
        ))}
      </tbody>
    </table>
  );
}
