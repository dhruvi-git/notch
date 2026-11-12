import Link from "next/link";
import styles from "./connect-repo.module.css";

/** The zero-effort way in: one command in the repository, then Notch captures by itself. */
export function ConnectRepo({ projectId, compact = false }: { projectId: string; compact?: boolean }) {
  return (
    <section className={styles.card} data-compact={compact} aria-labelledby="connect-title">
      <h2 id="connect-title" className={styles.title}>
        {compact ? "Capture automatically" : "Let Notch capture your work"}
      </h2>
      <p>
        Run this once in your repository. After that, every Claude Code session and every commit is captured, and the code
        map stays current. No exports.
      </p>
      <pre className={styles.cmd}>{`notch link ${projectId}\nnotch init`}</pre>
      <p className={styles.note}>
        Needs the Notch CLI and a token from <Link href="/settings">Settings</Link>. Coming back later? Run{" "}
        <code>notch resume</code>, or open Claude Code in the repository: it starts with a briefing.
      </p>
    </section>
  );
}
