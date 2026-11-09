import { segments, snippet as cut } from "@/lib/highlight";

/** Text with marked spans. With `length`, shows a window around the first mark instead of everything. */
export function Highlighted({
  text,
  spans,
  length,
}: {
  text: string;
  spans: ReadonlyArray<readonly [number, number]>;
  length?: number;
}) {
  const view = length ? cut(text, spans, length) : { text, spans, cutStart: false, cutEnd: false };
  return (
    <>
      {view.cutStart ? "…" : null}
      {segments(view.text, view.spans).map((s, i) => (s.marked ? <mark key={i}>{s.text}</mark> : <span key={i}>{s.text}</span>))}
      {view.cutEnd ? "…" : null}
    </>
  );
}
