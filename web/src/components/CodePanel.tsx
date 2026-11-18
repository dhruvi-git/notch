import { tokenize } from "@/lib/syntax";
import styles from "./code-panel.module.css";

/**
 * A region of a candidate's own code, numbered with its real line numbers so it can be found in
 * the repository. Dark in both themes: code is the subject here, and the page frames it.
 */
export function CodePanel({
  path,
  code,
  language,
  startLine = 1,
  href,
  maxHeight,
  className,
  focus,
}: {
  path: string;
  code: string;
  language: string;
  startLine?: number;
  /** Link to the lines on GitHub. */
  href?: string;
  maxHeight?: string;
  className?: string;
  /** Line numbers [first, last] being asked about; the rest of the file is shown dimmed for context. */
  focus?: [number, number];
}) {
  const lines = code.replace(/\s+$/, "").split("\n");
  const end = startLine + lines.length - 1;
  const width = String(end).length;
  const slash = path.lastIndexOf("/");
  return (
    <figure className={`${styles.panel} ${className ?? ""}`}>
      <figcaption className={styles.head}>
        <span className={styles.path}>
          {slash >= 0 ? <span className={styles.dir}>{path.slice(0, slash + 1)}</span> : null}
          {path.slice(slash + 1)}
        </span>
        {href ? (
          <a className={styles.lines} href={href} target="_blank" rel="noopener noreferrer">
            Lines {startLine}–{end} on GitHub
          </a>
        ) : (
          <span className={styles.lines}>
            Lines {focus ? `${focus[0]}–${focus[1]}` : `${startLine}–${end}`}
          </span>
        )}
      </figcaption>
      <pre className={styles.code} style={{ maxHeight, ["--gutter" as string]: `${width + 2}ch` }} tabIndex={0}>
        <code>
          {lines.map((line, i) => (
            <span
              key={i}
              className={`${styles.line} ${
                focus ? (startLine + i >= focus[0] && startLine + i <= focus[1] ? styles.focused : styles.context) : ""
              }`}
            >
              <span className={styles.num} aria-hidden="true">
                {startLine + i}
              </span>
              <span className={styles.text}>
                {tokenize(line, language).map((t, j) =>
                  t.kind === "plain" ? t.text : (
                    <span key={j} className={styles[t.kind]}>
                      {t.text}
                    </span>
                  ),
                )}
                {"\n"}
              </span>
            </span>
          ))}
        </code>
      </pre>
    </figure>
  );
}

/** A GitHub link to a line range on the default branch. */
export function linesHref(repoUrl: string, path: string, start: number, end: number) {
  return `${repoUrl.replace(/\/$/, "")}/blob/HEAD/${path.split("/").map(encodeURIComponent).join("/")}#L${start}-L${end}`;
}
