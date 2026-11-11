import { track } from "@/events/track";

export async function POST(request: Request) {
  const { name, data } = await request.json();
  await track(name, data);
  return new Response(null, { status: 202 });
}
