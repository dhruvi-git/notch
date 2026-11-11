import { handleWebhook } from "@/billing/stripe-webhooks";

export async function POST(request: Request) {
  await handleWebhook(await request.text(), request.headers.get("stripe-signature") ?? "");
  return new Response(null, { status: 204 });
}
