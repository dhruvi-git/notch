import Link from "next/link";
import { NotchMark } from "@/components/Brand";
import { HeroDemo } from "./HeroDemo";
import styles from "./landing.module.css";

export function Landing() {
  return (
    <div className={styles.page}>
      <header className={styles.nav}>
        <span className={styles.wordmark}>
          <NotchMark size={22} />
          notch
        </span>
        <Link href="/sign-in" className="btn btn-quiet">
          Sign in
        </Link>
      </header>

      <main>
        <section className={styles.hero}>
          <div className={styles.heroText}>
            <h1 className={styles.title}>Every decision your project made, with the chat that made it.</h1>
            <p className={styles.lede}>
              Notch reads your ChatGPT and Claude conversations, keeps a ledger of what you decided and why, writes it
              into your coding agent&rsquo;s instructions, and tells you when new work goes against it.
            </p>
            <div className={styles.ctas}>
              <Link href="/sign-in" className="btn btn-primary">
                Start with one chat export
              </Link>
              <a href="#how" className={styles.textLink}>
                See how it works
              </a>
            </div>
          </div>
          <HeroDemo />
        </section>

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
                Your coding agent starts every session from zero. Notch writes the decisions in force into{" "}
                <code>AGENTS.md</code> or <code>CLAUDE.md</code>, or a short briefing to paste into any chat.
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
                When a later chat starts using something a decision ruled out, or two decisions disagree, Notch shows
                both sides and lets you settle it in one step.
              </p>
            </div>
            <div className={styles.artifact} data-tone="warn">
              <p className={styles.artifactStrong}>A chat uses something a decision ruled out</p>
              <p>
                <mark>For raw events you could set up MongoDB</mark>, but on 5 October the project chose PostgreSQL
                instead.
              </p>
            </div>
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

        <section className={styles.closing}>
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
        <span>The decision record for AI-assisted projects.</span>
      </footer>
    </div>
  );
}
