// Exported invoices as CSV before the dashboard existed.
export function invoicesToCsv(rows: { id: string; total: number }[]): string {
  return rows.map((r) => `${r.id},${r.total}`).join("\n");
}
