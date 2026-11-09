import Link from "next/link";
import type { Project } from "@/lib/types";
import styles from "./shell.module.css";

/** A native disclosure: works without JavaScript and closes when you navigate. */
export function ProjectSwitcher({ current, projects }: { current: Project; projects: Project[] }) {
  return (
    <details className={styles.switcher}>
      <summary className={styles.switcherButton}>
        <span className={styles.switcherName}>{current.name}</span>
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <path d="M3 4.5 6 7.5 9 4.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
        </svg>
      </summary>
      <div className={styles.switcherMenu}>
        <ul>
          {projects.map((p) => (
            <li key={p.id}>
              <Link href={`/p/${p.id}`} aria-current={p.id === current.id ? "true" : undefined}>
                {p.name}
              </Link>
            </li>
          ))}
        </ul>
        <Link href="/projects" className={styles.switcherAll}>
          All projects
        </Link>
      </div>
    </details>
  );
}
