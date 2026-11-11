import { saveEvent } from "./store";

export async function track(name: string, data: object = {}): Promise<void> {
  try {
    await saveEvent(name, data);
  } catch {
    // Analytics must never break a request.
  }
}
