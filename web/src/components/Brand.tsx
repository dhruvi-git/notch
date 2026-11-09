import Link from "next/link";
import styles from "./brand.module.css";

/** The Notch mark: a tally stick with a notch cut into it, the oldest way to keep a record. */
export function NotchMark({ size = 20 }: { size?: number }) {
  return (
    <svg width={size * 0.7} height={size} viewBox="0 0 14 20" aria-hidden="true" className={styles.mark}>
      <path d="M3 0h7a2 2 0 0 1 2 2v5.5L8.5 10 12 12.5V18a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V2a2 2 0 0 1 2-2Z" />
    </svg>
  );
}

export function Brand({ href = "/projects" }: { href?: string }) {
  return (
    <Link href={href} className={styles.brand} aria-label="Notch home">
      <NotchMark />
      <span>notch</span>
    </Link>
  );
}
