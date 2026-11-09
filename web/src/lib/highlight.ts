/** A run of text, marked or not, ready to render. */
export type Segment = { text: string; marked: boolean };

/**
 * Splits text into marked and unmarked runs. Spans are [start, end) character offsets; they
 * may overlap or arrive unsorted, and spans outside the text are clipped.
 */
export function segments(text: string, spans: ReadonlyArray<readonly [number, number]>): Segment[] {
  const clean = spans
    .map(([s, e]) => [Math.max(0, Math.min(s, text.length)), Math.max(0, Math.min(e, text.length))] as const)
    .filter(([s, e]) => e > s)
    .sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const merged: [number, number][] = [];
  for (const [s, e] of clean) {
    const last = merged[merged.length - 1];
    if (last && s <= last[1]) last[1] = Math.max(last[1], e);
    else merged.push([s, e]);
  }
  const out: Segment[] = [];
  let at = 0;
  for (const [s, e] of merged) {
    if (s > at) out.push({ text: text.slice(at, s), marked: false });
    out.push({ text: text.slice(s, e), marked: true });
    at = e;
  }
  if (at < text.length) out.push({ text: text.slice(at), marked: false });
  return out;
}

const WORD = /[\p{L}\p{N}_]/u;

/**
 * Widens each span to the word it sits in, so a stem match ("Postgre") highlights the whole
 * word ("Postgres") rather than cutting it.
 */
export function toWords(text: string, spans: ReadonlyArray<readonly [number, number]>): [number, number][] {
  return spans.map(([s, e]) => {
    let a = Math.max(0, s);
    let b = Math.min(text.length, e);
    while (a > 0 && WORD.test(text[a - 1])) a--;
    while (b < text.length && WORD.test(text[b])) b++;
    return [a, b];
  });
}

export type Snippet = { text: string; spans: [number, number][]; cutStart: boolean; cutEnd: boolean };

/**
 * A window of about `length` characters around the first span, cut at word boundaries, with
 * spans shifted into the window. Short texts are returned whole.
 */
export function snippet(text: string, spans: ReadonlyArray<readonly [number, number]>, length = 320): Snippet {
  if (text.length <= length) return { text, spans: spans.map(([s, e]) => [s, e]), cutStart: false, cutEnd: false };
  const first = spans.length ? Math.min(...spans.map(([s]) => s)) : 0;
  let start = Math.max(0, first - Math.floor(length / 3));
  let end = Math.min(text.length, start + length);
  start = Math.max(0, end - length);
  if (start > 0) {
    const space = text.indexOf(" ", start);
    if (space !== -1 && space < first) start = space + 1;
  }
  if (end < text.length) {
    const space = text.lastIndexOf(" ", end);
    if (space > start + length / 2) end = space;
  }
  const shifted = spans
    .map(([s, e]) => [s - start, e - start] as [number, number])
    .filter(([s, e]) => e > 0 && s < end - start)
    .map(([s, e]) => [Math.max(0, s), Math.min(end - start, e)] as [number, number]);
  return { text: text.slice(start, end), spans: shifted, cutStart: start > 0, cutEnd: end < text.length };
}
