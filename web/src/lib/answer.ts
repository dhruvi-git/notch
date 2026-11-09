/** A piece of an answer: plain text, or a citation number pointing at a source. */
export type AnswerPart = { kind: "text"; text: string } | { kind: "cite"; n: number };

export type ParsedAnswer = { intro: AnswerPart[] | null; items: AnswerPart[][]; paragraphs: AnswerPart[][] };

const CITE = /\[(\d{1,2})\]/g;

/** Splits "We chose X [1] and Y [2]." into text and citation parts. */
export function parts(line: string): AnswerPart[] {
  const out: AnswerPart[] = [];
  let at = 0;
  for (const m of line.matchAll(CITE)) {
    const idx = m.index ?? 0;
    const before = line.slice(at, idx);
    if (before) out.push({ kind: "text", text: before });
    out.push({ kind: "cite", n: Number(m[1]) });
    at = idx + m[0].length;
  }
  const rest = line.slice(at);
  if (rest) out.push({ kind: "text", text: rest });
  // Drop the space left before a citation marker, so "joins [1]" renders as "joins¹".
  return out.map((p, i) =>
    p.kind === "text" && out[i + 1]?.kind === "cite" ? { ...p, text: p.text.replace(/\s+$/, "") } : p,
  );
}

/**
 * Reads an answer in the shape the API returns: an optional intro line, then "- " bullet
 * lines, or plain paragraphs. Citations become separate parts so they can link to sources.
 */
export function parseAnswer(text: string): ParsedAnswer {
  const lines = text.split(/\r?\n/).map((l) => l.trimEnd()).filter((l) => l.trim() !== "");
  const items: AnswerPart[][] = [];
  const paragraphs: AnswerPart[][] = [];
  let intro: AnswerPart[] | null = null;
  for (const line of lines) {
    const bullet = line.match(/^\s*[-*•]\s+(.*)$/);
    if (bullet) items.push(parts(bullet[1]));
    else if (items.length === 0 && intro === null && lines.some((l) => /^\s*[-*•]\s+/.test(l))) intro = parts(line);
    else paragraphs.push(parts(line));
  }
  return { intro, items, paragraphs };
}

/** Citation numbers in the order they first appear. */
export function citedNumbers(a: ParsedAnswer): number[] {
  const seen: number[] = [];
  for (const group of [a.intro ?? [], ...a.items, ...a.paragraphs]) {
    for (const p of group) if (p.kind === "cite" && !seen.includes(p.n)) seen.push(p.n);
  }
  return seen;
}
