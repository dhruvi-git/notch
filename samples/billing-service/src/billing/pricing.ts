/** Line totals, tax and discounts, in cents to avoid rounding errors. */
export type Line = { quantity: number; unitCents: number };

export function subtotal(lines: Line[]): number {
  return lines.reduce((sum, l) => sum + l.quantity * l.unitCents, 0);
}

export function withTax(cents: number, rate = 0.18): number {
  return Math.round(cents * (1 + rate));
}

export function formatCents(cents: number): string {
  return `₹${(cents / 100).toFixed(2)}`;
}
