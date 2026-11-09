"use client";

import styles from "./error.module.css";

/** Shown when a page fails to load, most often because the API is not running. */
export default function AppError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const unreachable = error.message.includes("Can't reach the Notch API");
  return (
    <main className={styles.wrap}>
      <h1>{unreachable ? "Notch can't reach its server" : "This page didn't load"}</h1>
      <p className="muted">
        {unreachable
          ? "The API isn't responding. If you're running Notch locally, start it with mvn -pl api spring-boot:run, then try again."
          : "Something went wrong while loading. Try again; if it keeps happening, reload the page."}
      </p>
      <button type="button" className="btn btn-primary" onClick={reset}>
        Try again
      </button>
    </main>
  );
}
