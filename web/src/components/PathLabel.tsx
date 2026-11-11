import { splitPath } from "@/lib/code";

/** A file path with the folder quiet and the file name prominent. */
export function PathLabel({ path, className }: { path: string; className?: string }) {
  const [dir, name] = splitPath(path);
  return (
    <span className={className} style={{ fontFamily: "var(--font-mono)", fontSize: "0.92em", wordBreak: "break-all" }}>
      <span style={{ color: "var(--ink-soft)" }}>{dir}</span>
      <span style={{ color: "var(--ink)", fontWeight: 600 }}>{name}</span>
    </span>
  );
}
