import { useId } from "react";
import styles from "./seal.module.css";

/**
 * The Defended seal: given only when a candidate has explained their own code in an Ownership
 * Check and passed. It is the one ornamental object in Notch, so it is never used for anything
 * else. Text runs round the ring like a notary's stamp; the score sits in the middle.
 */
export function Seal({
  score,
  size = 120,
  stamp = false,
  tone = "plum",
  label,
}: {
  score?: number | null;
  size?: number;
  /** Plays the stamping motion once (respects reduced motion). */
  stamp?: boolean;
  /** "plum" on light pages, "paper" on the dark stage. */
  tone?: "plum" | "paper";
  label?: string;
}) {
  const id = useId().replace(/:/g, "");
  // One phrase, spread evenly round the whole ring.
  const text = "DEFENDED ✦ OWNERSHIP CHECK ✦ NOTCH ✦ ";
  const name = label ?? (score != null ? `Defended in an Ownership Check, score ${Math.round(score)}` : "Defended in an Ownership Check");
  return (
    <svg
      className={`${styles.seal} ${tone === "paper" ? styles.paper : styles.plum} ${stamp ? styles.stamp : ""}`}
      width={size}
      height={size}
      viewBox="0 0 120 120"
      role="img"
      aria-label={name}
    >
      <defs>
        <path id={`ring-${id}`} d="M60,60 m-45,0 a45,45 0 1,1 90,0 a45,45 0 1,1 -90,0" />
      </defs>
      {/* scalloped edge: 36 small bites, like a pressed seal */}
      <circle className={styles.disc} cx="60" cy="60" r="58" />
      <circle className={styles.edge} cx="60" cy="60" r="56.5" />
      <circle className={styles.inner} cx="60" cy="60" r="36" />
      <text className={styles.ringText}>
        <textPath href={`#ring-${id}`} textLength="282" lengthAdjust="spacing">
          {text}
        </textPath>
      </text>
      {score != null ? (
        <>
          <text className={styles.score} x="60" y="66" textAnchor="middle">
            {Math.round(score)}
          </text>
          <path className={styles.notch} d="M52 74 h16" />
        </>
      ) : (
        <path className={styles.check} d="M47 61 l9 9 l17 -19" />
      )}
    </svg>
  );
}

/** The inline version for chips and table rows: a small filled ring. */
export function SealDot({ className }: { className?: string }) {
  return (
    <svg className={`${styles.dot} ${className ?? ""}`} viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <circle cx="8" cy="8" r="7" />
      <circle cx="8" cy="8" r="4.25" />
    </svg>
  );
}
