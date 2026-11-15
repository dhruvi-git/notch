"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import styles from "./review.module.css";

/** Shown while a review runs: refreshes the page every few seconds until it's done. */
export function Pending({ label, createdAt }: { label: string; createdAt: string }) {
  const router = useRouter();
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const tick = setInterval(() => setNow(Date.now()), 1000);
    const poll = setInterval(() => router.refresh(), 3000);
    return () => {
      clearInterval(tick);
      clearInterval(poll);
    };
  }, [router]);

  const seconds = Math.max(0, Math.round((now - new Date(createdAt).getTime()) / 1000));
  return (
    <section className={styles.pending} aria-live="polite">
      <span className={styles.spinner} aria-hidden="true" />
      <div>
        <h2>Reviewing {label}…</h2>
        <p>
          Notch is checking decisions, secrets and structure while Claude reads the most important code. This usually takes
          a minute or two. {seconds > 0 ? `${seconds} s so far.` : null}
        </p>
      </div>
    </section>
  );
}
