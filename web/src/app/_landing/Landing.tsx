import Link from "next/link";
import { NotchMark } from "@/components/Brand";
import { HeroDemo } from "./HeroDemo";
import styles from "./landing.module.css";

export function Landing() {
  return (
    <div className={styles.page}>
      <div className={`${styles.top} on-brand`}>
      <header className={styles.nav}>
        <span className={styles.wordmark}>
          <NotchMark size={24} />
          notch
        </span>
        <Link href="/sign-in" className="btn btn-quiet">
          Sign in
        </Link>
      </header>

        <section className={styles.hero}>
          <div className={styles.heroText}>
            <h1 className={styles.title}>Every decision your project made, with the chat that made it.</h1>
            <p className={styles.lede}>
              Notch keeps a ledger of what your project decided and why, from your chats and coding sessions. Every
              coding agent can read it, and commits or edits that go against it are stopped before they land.
            </p>
            <div className={styles.ctas}>
              <Link href="/sign-in" className="btn btn-yellow">
                Start with one chat export
              </Link>
              <a href="#how" className={styles.textLink}>
                See how it works
              </a>
            </div>
          </div>
          <HeroDemo />
        </section>
      </div>

      <main>

        <section id="how" className={styles.how} aria-labelledby="how-title">
          <h2 id="how-title" className={styles.sectionTitle}>
            How it works
          </h2>

          <div className={styles.part}>
            <div>
              <h3>Capture</h3>
              <p>
                Import an export or paste a transcript. Notch hides secrets before anything is saved, skips passages it
                already has, and pulls out decisions, open tasks and fixes, each linked to the sentence it came from.
              </p>
            </div>
            <div className={styles.artifact}>
              <p className={styles.artifactStrong}>11 new messages from 2 conversations</p>
              <p>1 secret hidden before saving. 3 repeated passages skipped. 7 decisions, tasks and fixes found.</p>
            </div>
          </div>

          <div className={styles.part}>
            <div>
              <h3>Sync</h3>
              <p>
                Your coding agent starts every session from zero. Notch gives Claude Code, Codex and Cursor your
                decisions over MCP, and writes them into <code>AGENTS.md</code> or <code>CLAUDE.md</code>.
              </p>
            </div>
            <pre className={styles.code}>{`# AGENTS.md

These decisions were made earlier in the project.
Follow them unless the user says otherwise.

## Decisions in force
- Use server-side sessions instead of JWT.
  (decided 2026-11-08; rejected: JWT)
- Let's go with Postgres instead of MongoDB.
  (decided 2026-10-05; rejected: MongoDB)`}</pre>
          </div>

          <div className={styles.part}>
            <div>
              <h3>Guard</h3>
              <p>
                A commit or an agent&rsquo;s edit that brings back something you ruled out is stopped before it lands,
                with the decision it breaks. Conflicts in chats are settled in one step.
              </p>
            </div>
            <pre className={`${styles.code} ${styles.terminal}`}>{`$ git commit -m "Store raw events"

Notch: 1 change goes against the project's decisions

  HIGH   package.json:5
         "mongoose": "^8.4.0"
         Adds MongoDB (mongoose), but on 5 Oct the project
         chose PostgreSQL and ruled it out.

Commit stopped.`}</pre>
          </div>
        </section>

        <section className={styles.private} aria-labelledby="private-title">
          <h2 id="private-title" className={styles.sectionTitle}>
            Your chats stay yours
          </h2>
          <p>
            Keys and tokens are removed before anything is stored. Message text is encrypted, and tied to your account so
            it can&rsquo;t be read from anyone else&rsquo;s. Projects marked private are never sent to an AI model;
            search and decisions still work.
          </p>
        </section>

        <section className={`${styles.closing}`}>
          <p className={styles.closingLine}>Start with the chat where you made your last big decision.</p>
          <Link href="/sign-in" className="btn btn-primary">
            Sign in with GitHub
          </Link>
        </section>
      </main>

      <footer className={styles.footer}>
        <span className={styles.wordmarkSmall}>
          <NotchMark size={16} />
          notch
        </span>
        <span>The project brain every coding agent plugs into.</span>
      </footer>
    </div>
  );
}
