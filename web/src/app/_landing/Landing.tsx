import Link from "next/link";
import { NotchMark } from "@/components/Brand";
import { HeroDemo } from "./HeroDemo";
import styles from "./landing.module.css";

const FEATURES = [
  {
    name: "Review your code",
    text: "An honest assessment of the whole codebase: what each part does well, the issues with file and line, and what to fix first.",
    where: "Review",
  },
  {
    name: "See how it fits together",
    text: "A map of the code: what every file does, where to start reading, and what a change to a file could affect.",
    where: "Explain",
  },
  {
    name: "Keep the decisions",
    text: "What was chosen, why, and what was ruled out, plus open tasks and the fixes for errors you hit. Collected from your sessions and commits as you work.",
    where: "Ledger",
  },
  {
    name: "Pick up where you left off",
    text: "Your last session, the work you haven't committed, and the next tasks, in one place when you come back.",
    where: "Where you stopped",
  },
  {
    name: "Ask your project",
    text: "Search everything Notch holds, or ask a question and get an answer that quotes its source.",
    where: "Ask",
  },
  {
    name: "Catch drift",
    text: "Code, chats and commits that go against a decision are flagged, and a commit that breaks one is stopped before it lands.",
    where: "Drift",
  },
];

const FAQ = [
  {
    q: "Do I need to upload chat exports?",
    a: "No. Your code is enough to start: a review and a map need nothing else. Connect the repository and Notch collects decisions from your Claude Code sessions and commits by itself. Chat exports from ChatGPT or Claude are an optional extra.",
  },
  {
    q: "Is my code stored?",
    a: "No. Uploaded code is read in memory and discarded once the review is written. Notch keeps the review and a map of file names, imports and summaries, encrypted and tied to your account.",
  },
  {
    q: "Which languages does it understand?",
    a: "Reviews work for any language. The map understands imports and routes in TypeScript and JavaScript (including Next.js and Express), Python, Java and Go.",
  },
  {
    q: "Does it work with my coding agent?",
    a: "Yes. Claude Code gets a /notch command and a briefing at the start of every session. Codex and Cursor connect to the same decisions over MCP.",
  },
];

export function Landing({ signedIn = false }: { signedIn?: boolean }) {
  const start = signedIn ? "/projects" : "/sign-in";
  return (
    <div className={styles.page}>
      <div className={`${styles.top} on-brand`}>
        <header className={styles.nav}>
          <Link href="/" className={styles.wordmark}>
            <NotchMark size={24} />
            notch
          </Link>
          <nav aria-label="On this page" className={styles.navLinks}>
            <a href="#features">What it does</a>
            <a href="#how">How it works</a>
            <a href="#claude-code">Claude Code</a>
            <a href="#privacy">Privacy</a>
          </nav>
          <Link href={start} className={signedIn ? "btn btn-yellow" : "btn btn-quiet"}>
            {signedIn ? "Open your projects" : "Sign in"}
          </Link>
        </header>

        <section className={styles.hero}>
          <div className={styles.heroText}>
            <h1 className={styles.title}>Understand your code, and remember why it&rsquo;s built that way.</h1>
            <p className={styles.lede}>
              Drop in a project folder. Notch reviews it, maps how the pieces fit together, and keeps the decisions behind
              it, so neither you nor your coding agent starts from zero.
            </p>
            <div className={styles.ctas}>
              <Link href={start} className="btn btn-yellow">
                {signedIn ? "Open your projects" : "Get started"}
              </Link>
              <a href="#how" className={styles.textLink}>
                See how it works
              </a>
            </div>
            {!signedIn ? <p className={styles.heroNote}>Sign in with Google or GitHub, then choose a folder.</p> : null}
          </div>
          <HeroDemo />
        </section>
      </div>

      <main>
        <section id="features" className={styles.section} aria-labelledby="features-title">
          <h2 id="features-title" className={styles.sectionTitle}>
            What Notch does
          </h2>
          <p className={styles.sectionLede}>Each of these is a page in the app. The label shows where to find it.</p>
          <ul className={styles.features}>
            {FEATURES.map((f) => (
              <li key={f.name} className={styles.feature}>
                <h3>{f.name}</h3>
                <p>{f.text}</p>
                <span className={styles.where}>{f.where}</span>
              </li>
            ))}
          </ul>
        </section>

        <section id="how" className={`${styles.section} ${styles.how}`} aria-labelledby="how-title">
          <h2 id="how-title" className={styles.sectionTitle}>
            How it works
          </h2>
          <ol className={styles.steps}>
            <li className={styles.step}>
              <div>
                <h3>Add your code</h3>
                <p>
                  Choose your project folder. There&rsquo;s nothing to zip: <code>node_modules</code>, <code>.git</code> and
                  build output stay on your computer. A zip works too, or connect the repository with <code>notch init</code>{" "}
                  and Notch keeps itself up to date.
                </p>
              </div>
              <div className={styles.artifact}>
                <p className={styles.artifactStrong}>Packing 50 files from worli-decision</p>
                <p>Left out: node_modules, .git, .next and local .env files.</p>
              </div>
            </li>
            <li className={styles.step}>
              <div>
                <h3>Read the review and the map</h3>
                <p>
                  In about a minute you get the overall picture, the issues ranked by how much they matter, and what to do
                  next. Explain shows how the files depend on each other.
                </p>
              </div>
              <div className={styles.artifact}>
                <p className={styles.counts}>
                  <span className={styles.count} data-severity="high"><strong>2</strong> High</span>
                  <span className={styles.count} data-severity="medium"><strong>8</strong> Medium</span>
                  <span className={styles.count}><strong>4</strong> Low</span>
                </p>
                <p>The funding solver tries every combination of properties, so large sheets freeze the tab.</p>
              </div>
            </li>
            <li className={styles.step}>
              <div>
                <h3>Keep working, and Notch keeps up</h3>
                <p>
                  Every Claude Code session and commit adds to the project&rsquo;s memory: decisions with their reasons, open
                  tasks, and how errors were fixed. Come back a week later and it&rsquo;s all there.
                </p>
              </div>
              <div className={styles.artifact}>
                <p className={styles.artifactStrong}>Decision: use Postgres for billing data</p>
                <p>Because we need joins and transactions. Ruled out: MongoDB. From the session on 5 October.</p>
              </div>
            </li>
          </ol>
        </section>

        <section id="claude-code" className={styles.section} aria-labelledby="cc-title">
          <div className={styles.split}>
            <div>
              <h2 id="cc-title" className={styles.sectionTitle}>
                Inside Claude Code
              </h2>
              <p className={styles.sectionLede}>
                After <code>notch init</code>, every Claude Code session starts with a briefing on where you stopped, and
                the <code>/notch</code> command is there when you need it.
              </p>
              <dl className={styles.commands}>
                <dt><code>/notch review</code></dt>
                <dd>Claude Code reviews the repository and saves the review to Notch.</dd>
                <dt><code>/notch resume</code></dt>
                <dd>Where work stopped, open tasks and anything that drifted.</dd>
                <dt><code>/notch why &lt;error&gt;</code></dt>
                <dd>What fixed this error last time, or a plain &ldquo;not seen before&rdquo;.</dd>
                <dt><code>/notch decisions</code></dt>
                <dd>The decisions in force, with why and what was ruled out.</dd>
              </dl>
            </div>
            <pre className={`${styles.code} ${styles.terminal}`}>{`$ git commit -m "Store raw events"

Notch: 1 change goes against the project's decisions

  HIGH   package.json:5
         "mongoose": "^8.4.0"
         Adds MongoDB, but on 5 Oct the project
         chose PostgreSQL and ruled it out.

Commit stopped.`}</pre>
          </div>
        </section>

        <section id="guide" className={styles.section} aria-labelledby="guide-title">
          <h2 id="guide-title" className={styles.sectionTitle}>
            Finding your way around
          </h2>
          <p className={styles.sectionLede}>Every project opens on its Overview. The menu groups the rest by what you&rsquo;re doing.</p>
          <div className={styles.guide}>
            <div>
              <h3>Your code</h3>
              <p><strong>Review</strong> for the assessment and issues, <strong>Explain</strong> for how it works, file by file.</p>
            </div>
            <div>
              <h3>Your project&rsquo;s memory</h3>
              <p>
                <strong>Where you stopped</strong>, <strong>Ask</strong>, the <strong>Ledger</strong> of decisions and tasks,{" "}
                <strong>Drift</strong>, and the <strong>Conversations</strong> it all came from.
              </p>
            </div>
            <div>
              <h3>Adding to it</h3>
              <p><strong>Upload</strong> a folder, zip or chat export, or <strong>Connect repo</strong> so it updates itself.</p>
            </div>
          </div>
        </section>

        <section id="privacy" className={`${styles.section} ${styles.private}`} aria-labelledby="private-title">
          <h2 id="private-title" className={styles.sectionTitle}>
            Your code stays yours
          </h2>
          <p>
            Keys and tokens are removed before anything is stored or sent to a model. Everything kept is encrypted and tied
            to your account, so no one else&rsquo;s can read it. Projects you mark private are never sent to an AI model;
            the review&rsquo;s automated checks, search and decisions still work.
          </p>
        </section>

        <section className={styles.section} aria-labelledby="faq-title">
          <h2 id="faq-title" className={styles.sectionTitle}>
            Questions
          </h2>
          <div className={styles.faq}>
            {FAQ.map((f) => (
              <details key={f.q}>
                <summary>{f.q}</summary>
                <p>{f.a}</p>
              </details>
            ))}
          </div>
        </section>

        <section className={styles.closing}>
          <p className={styles.closingLine}>Start with the project you&rsquo;re working on now.</p>
          <Link href={start} className="btn btn-primary">
            {signedIn ? "Open your projects" : "Get started"}
          </Link>
        </section>
      </main>

      <footer className={styles.footer}>
        <span className={styles.wordmarkSmall}>
          <NotchMark size={16} />
          notch
        </span>
        <span>Understand your code, and remember why it&rsquo;s built that way.</span>
      </footer>
    </div>
  );
}
