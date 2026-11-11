import { z } from "zod";
import { createInvoice } from "@/billing/invoices";
import { allow } from "@/lib/rate-limit";
import { currentSession } from "@/lib/session";

const Body = z.object({ lines: z.array(z.object({ quantity: z.number().int().positive(), unitCents: z.number().int() })) });

export async function POST(request: Request) {
  const session = await currentSession();
  if (!session) return new Response("Sign in first", { status: 401 });
  if (!(await allow(session.customerId))) return new Response("Too many requests", { status: 429 });
  const body = Body.parse(await request.json());
  return Response.json(await createInvoice(session.customerId, body.lines), { status: 201 });
}
