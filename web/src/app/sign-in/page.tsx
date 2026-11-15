import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { NotchMark } from "@/components/Brand";
import { publicApi, sessionToken } from "@/lib/server-api";
import type { AuthConfig } from "@/lib/types";
import styles from "./sign-in.module.css";

export const metadata: Metadata = { title: "Sign in" };

const MESSAGES: Record<string, string> = {
  access_denied: "Sign-in was cancelled.",
  code_expired: "That sign-in link expired. Start again.",
  github_failed: "GitHub didn't confirm the sign-in. Try again.",
  google_failed: "Google didn't confirm the sign-in. Try again.",
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
  const anyProvider = config !== null && (config.google || config.github);

  return (
    <main className={styles.page}>
      <section className={`${styles.brandSide} on-brand`} aria-labelledby="sign-in-title">
        <Link href="/" className={styles.wordmark}>
          <NotchMark size={28} />
          <span>notch</span>
        </Link>
        <h1 id="sign-in-title" className={styles.title}>
          Understand your code, and remember why it&rsquo;s built that way.
        </h1>
        <p className={styles.lede}>
          Drop in a project and Notch reviews it, maps how it works, and keeps the decisions behind it, on the web and inside
          Claude Code.
        </p>
        <ul className={styles.points}>
          <li>A full code review with issues by file and line</li>
          <li>A map of how the code fits together</li>
          <li>Decisions and open tasks every coding agent follows</li>
        </ul>
        <Link href="/" className={styles.back}>
          &larr; What is Notch?
        </Link>
      </section>

      <section className={styles.formSide} aria-label="Sign in">
        <div className={styles.formInner}>
          <h2 className={styles.formTitle}>Sign in to Notch</h2>
          <p className={styles.formSub}>New here? Signing in creates your account.</p>
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
              {config.google ? (
                <a href="/auth/google" className={`btn ${styles.google}`}>
                  <GoogleIcon />
                  Continue with Google
                </a>
              ) : null}
              {config.github ? (
                <a href="/auth/github" className={`btn ${styles.github}`}>
                  <svg width="18" height="18" viewBox="0 0 16 16" aria-hidden="true" fill="currentColor">
                    <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
                  </svg>
                  Continue with GitHub
                </a>
              ) : null}
              {config.devLogin ? (
                <form action="/auth/dev" method="post" className={styles.devForm}>
                  {anyProvider ? <p className={styles.divider}><span>or, on this computer</span></p> : null}
                  <label htmlFor="handle" className={styles.devLabel}>
                    Local development: sign in as a test user
                  </label>
                  <div className={styles.devRow}>
                    <input id="handle" name="handle" className="field" defaultValue="dev" autoComplete="off" />
                    <button type="submit" className={anyProvider ? "btn btn-quiet" : "btn btn-primary"}>
                      Sign in
                    </button>
                  </div>
                </form>
              ) : null}
              {!config.google && !config.github && !config.devLogin ? (
                <p className={styles.notice}>No sign-in method is set up on this server yet.</p>
              ) : null}
            </div>
          )}
          <p className={styles.fine}>
            Your code and chats are encrypted and tied to your account. Projects you mark private are never sent to an AI
            model.
          </p>
        </div>
      </section>
    </main>
  );
}

/** Google's "G", as its sign-in guidelines ask buttons to show. */
function GoogleIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true">
      <path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.4-.4-3.5z" />
      <path fill="#FF3D00" d="M6.3 14.7l6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z" />
      <path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-8l-6.5 5C9.5 39.6 16.2 44 24 44z" />
      <path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.4-.4-3.5z" />
    </svg>
  );
}
