/** Small formatting helpers shared by server and client components. */

export function plural(n: number, one: string, many = one + "s"): string {
  return `${n.toLocaleString("en")} ${n === 1 ? one : many}`;
}

const DAY = 86_400_000;

/** "Just now", "12 min ago", "Yesterday", "3 days ago", then a date. */
export function relativeTime(iso: string, now: Date = new Date()): string {
  const t = new Date(iso).getTime();
  const diff = now.getTime() - t;
  if (diff < 60_000) return "Just now";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} min ago`;
  if (diff < DAY && new Date(t).getDate() === now.getDate()) return `${Math.floor(diff / 3_600_000)} h ago`;
  if (diff < 2 * DAY) return "Yesterday";
  if (diff < 7 * DAY) return `${Math.floor(diff / DAY)} days ago`;
  return shortDate(iso, now);
}

export function shortDate(iso: string, now: Date = new Date()): string {
  const d = new Date(iso);
  const sameYear = d.getFullYear() === now.getFullYear();
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short", ...(sameYear ? {} : { year: "numeric" }) });
}

export const SOURCE_LABEL: Record<string, string> = {
  chatgpt: "ChatGPT",
  claude: "Claude",
  markdown: "Markdown",
  text: "Text",
};
