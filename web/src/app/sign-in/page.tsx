import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { NotchMark } from "@/components/Brand";
import { publicApi, sessionToken } from "@/lib/server-api";
import type { AuthConfig } from "@/lib/types";
import styles from "./sign-in.module.css";

export const metadata: Metadata = { title: "Sign in" };

const MESSAGES: Record<string, string> = {
  access_denied: "GitHub sign-in was cancelled.",
  code_expired: "That sign-in link expired. Start again.",
  github_failed: "GitHub didn't confirm the sign-in. Try again.",
  missing_code: "The sign-in didn't finish. Start again.",
  api_unreachable: "Notch's server isn't reachable right now. Try again in a moment.",
  dev_login_off: "Test sign-in is only available when the server runs locally.",
  invalid_handle: "Use letters, digits, - or _ for the test user name.",
};

type Search = { error?: string; expired?: string; signed_out?: string };

export default async function SignIn({ searchParams }: { searchParams: Promise<Search> }) {
  if (await sessionToken()) redirect("/projects");
  const params = await searchParams;
  let config: AuthConfig | null = null;
  try {
    config = await publicApi<AuthConfig>("/auth/config");
  } catch {
    config = null;
  }

  const notice = params.error
    ? (MESSAGES[params.error] ?? "Sign-in didn't work. Try again.")
    : params.expired
      ? "Your session ended. Sign in again to continue."
      : params.signed_out
        ? "You're signed out."
        : null;

  return (
    <main className={styles.page}>
      <section className={`${styles.brandSide} on-brand`} aria-labelledby="sign-in-title">
        <p className={styles.wordmark}>
          <NotchMark size={28} />
          <span>notch</span>
        </p>
        <h1 id="sign-in-title" className={styles.title}>
          Keep your project&rsquo;s decisions, with the conversation that made them.
        </h1>
        <p className={styles.lede}>
          Notch reads your ChatGPT, Claude and coding-agent chats, records what you decided and what&rsquo;s left to do,
          and stops new work that goes against an earlier decision.
        </p>
        <div className={styles.specimen} aria-label="Example of a recorded decision">
          <figure className={styles.entry}>
            <blockquote>
              <p>
                <mark>Let&rsquo;s go with Postgres instead of MongoDB</mark> because we need joins and real transactions
                for payments.
                <sup className={styles.cite}>1</sup>
              </p>
            </blockquote>
            <figcaption>
              <span className="chip chip-ok">Decision in force</span>
              <span>Billing service, 5 October</span>
            </figcaption>
          </figure>
          <figure className={`${styles.entry} ${styles.drift}`}>
            <blockquote>
              <p>
                For raw events you could <mark>set up MongoDB: run <code>npm install mongoose</code></mark>
              </p>
            </blockquote>
            <figcaption>
              <span className="chip chip-warn">Goes against decision 1</span>
              <span>Analytics service, today</span>
            </figcaption>
          </figure>
        </div>
      </section>

      <section className={styles.formSide} aria-label="Sign in">
        <div className={styles.formInner}>
          <h2 className={styles.formTitle}>Sign in</h2>
          {notice ? (
            <p className={styles.notice} role="status">
              {notice}
            </p>
          ) : null}

          {config === null ? (
            <p className={styles.notice} role="alert">
              Notch&rsquo;s server isn&rsquo;t reachable, so sign-in is unavailable. If you&rsquo;re running locally, start
              the API with <code>mvn -pl api spring-boot:run</code>.
            </p>
          ) : (
            <div className={styles.actions}>
              {config.github ? (
                <a href="/auth/github" className="btn btn-primary">
                  <svg width="18" height="18" viewBox="0 0 16 16" aria-hidden="true" fill="currentColor">
                    <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
                  </svg>
                  Continue with GitHub
                </a>
              ) : null}
              {config.devLogin ? (
                <form action="/auth/dev" method="post" className={styles.devForm}>
                  <label htmlFor="handle" className={styles.devLabel}>
                    Local development: sign in as a test user
                  </label>
                  <div className={styles.devRow}>
                    <input id="handle" name="handle" className="field" defaultValue="dev" autoComplete="off" />
                    <button type="submit" className={config.github ? "btn btn-quiet" : "btn btn-primary"}>
                      Sign in
                    </button>
                  </div>
                </form>
              ) : null}
              {!config.github && !config.devLogin ? (
                <p className={styles.notice}>No sign-in method is set up on this server yet.</p>
              ) : null}
            </div>
          )}
        </div>
      </section>
    </main>
  );
}
