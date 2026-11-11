/** Payment status from Stripe webhooks; polling Stripe would hit its rate limits. */
import Stripe from "stripe";
import { markPaid } from "./invoices";

const stripe = new Stripe(process.env.STRIPE_SECRET_KEY ?? "");

export async function handleWebhook(body: string, signature: string): Promise<void> {
  const event = stripe.webhooks.constructEvent(body, signature, process.env.STRIPE_WEBHOOK_SECRET ?? "");
  if (event.type === "invoice.paid") {
    const invoiceId = (event.data.object as { metadata?: { invoiceId?: string } }).metadata?.invoiceId;
    if (invoiceId) await markPaid(invoiceId);
  }
}
