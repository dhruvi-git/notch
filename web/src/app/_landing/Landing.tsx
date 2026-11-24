import Link from "next/link";
import { NotchMark } from "@/components/Brand";
import { Seal, SealDot } from "@/components/Seal";
import { Viva } from "./Viva";
import styles from "./landing.module.css";

/**
 * The four evidence levels, as the engine scores them: the most one piece of evidence can count
 * for (SkillScoring strengths: project 0.4, repository 0.85, defended repository 1.0).
 */
const LEVELS = [
  { level: "Claimed", cap: 0, title: "You typed it in", text: "Shows on your profile. Counts for nothing until work backs it." },
  { level: "Shown", cap: 40, title: "There's an artifact", text: "A project or write-up that uses the skill, with a link anyone can open." },
  { level: "Verified", cap: 85, title: "The commits check out", text: "Your own commits across weeks. Not a fork, not a copy of someone else's repository." },
  {
    level: "Defended",
    cap: 100,
    title: "You explained it",
    text: "You answered questions about your own code against the clock, and passed. The transcript is public.",
  },
];

const STEPS = [
  { title: "Connect GitHub", text: "Notch reads your public repositories: your own commits, the weeks you worked, the tools in each." },
  { title: "Add what GitHub can't see", text: "Hackathons, a club you run, a weekend build. Optionally a ChatGPT or Claude export." },
  { title: "Defend a repository", text: "Twelve minutes, five questions about code you wrote. Pass, and it's Defended." },
  { title: "Share one link", text: "Your skills, the proof behind each one, and the transcripts of every repository you defended." },
];

/** An excerpt of a real shortlist screen, with sample candidates. */
const SHORTLIST = [
  { handle: "asha-r", note: "Data analyst, Pune", score: 91, level: "Defended", skills: "SQL 93, Python 88" },
  { handle: "rohan-k", note: "ML engineer", score: 86, level: "Defended", skills: "Python 92, SQL 66" },
  { handle: "kabir-dev", note: "Data and backend", score: 83, level: "Verified", skills: "SQL 88, Python 82" },
  { handle: "meera-s", note: "Analytics, BI", score: 69, level: "Verified", skills: "SQL 72, Python 63" },
];

export function Landing({ signedIn = false }: { signedIn?: boolean }) {
  const build = signedIn ? "/start" : "/sign-in";
  const hire = signedIn ? "/hire" : "/sign-in";
  return (
    <div className={styles.page}>
      <div className={`${styles.top} on-brand`}>
        <header className={styles.nav}>
          <Link href="/" className={styles.wordmark}>
            <NotchMark size={24} />
            notch
          </Link>
          <nav aria-label="On this page" className={styles.navLinks}>
            <a href="#levels">How it works</a>
            <a href="#students">For students</a>
            <a href="#recruiters">For recruiters</a>
          </nav>
          <Link href={signedIn ? "/start" : "/sign-in"} className="btn btn-quiet">
            {signedIn ? "Your read" : "Sign in"}
          </Link>
        </header>

        <section className={styles.hero} aria-labelledby="hero-title">
          <h1 id="hero-title" className={styles.title}>
            <span>You wrote it.</span> <span>Now prove it.</span>
          </h1>
          <div className={styles.heroSide}>
            <p className={styles.lede}>
              Notch asks you about your own code, the functions and queries and the choices behind them, and turns what you can explain into a
              profile recruiters trust more than any resume.
            </p>
            <div className={styles.ctas}>
              <Link href={build} className="btn btn-yellow">
                Build my profile
              </Link>
              <Link href={hire} className="btn btn-quiet">
                I&rsquo;m hiring
              </Link>
            </div>
            <p className={styles.heroNote}>Free for students. Sign in with GitHub or Google.</p>
          </div>
        </section>
      </div>

      <div className={styles.vivaWrap}>
        <Viva />
      </div>

      <main>
        <section id="levels" className={styles.section} aria-labelledby="levels-title">
          <div className={styles.sectionHead}>
            <h2 id="levels-title" className={styles.sectionTitle}>
              Four levels. Only one you can&rsquo;t fake.
            </h2>
            <p className={styles.sectionLede}>
              A skill&rsquo;s score comes from the evidence behind it, and each piece can only carry it so far. One repository tops out at 85.
              Explain the code yourself and that ceiling goes.
            </p>
          </div>
          <ol className={styles.ladder}>
            {LEVELS.map((l, i) => (
              <li key={l.level} className={`${styles.rung} ${l.level === "Defended" ? styles.rungTop : ""}`} style={{ ["--h" as string]: i }}>
                <div className={styles.rungHead}>
                  <span className={styles.rungLevel}>
                    {l.level === "Defended" ? <SealDot /> : null}
                    {l.level}
                  </span>
                  <span className={styles.rungCap}>
                    {l.cap === 0 ? "Scores 0" : `Up to ${l.cap}`}
                  </span>
                </div>
                {l.level === "Defended" ? (
                  <div className={styles.rungSeal}>
                    <Seal size={92} tone="paper" />
                  </div>
                ) : null}
                <h3>{l.title}</h3>
                <p>{l.text}</p>
              </li>
            ))}
          </ol>
        </section>

        <section id="students" className={styles.section} aria-labelledby="students-title">
          <div className={styles.sectionHead}>
            <h2 id="students-title" className={styles.sectionTitle}>
              From GitHub to a defended profile in an afternoon
            </h2>
          </div>
          <ol className={styles.steps}>
            {STEPS.map((s, i) => (
              <li key={s.title}>
                <span className={styles.stepNum}>{i + 1}</span>
                <h3>{s.title}</h3>
                <p>{s.text}</p>
              </li>
            ))}
          </ol>
        </section>

        <section id="recruiters" className={styles.section} aria-labelledby="recruiters-title">
          <div className={styles.recruiters}>
            <div className={styles.recruitersText}>
              <h2 id="recruiters-title" className={styles.sectionTitle}>
                Hiring freshers? Ask for Defended.
              </h2>
              <p className={styles.sectionLede}>
                Paste a job description. Notch reads the skills it asks for and ranks every candidate on evidence, with the score broken down skill
                by skill. Require Defended for the skills that matter, and read how each candidate explained their own code before you call them.
              </p>
              <ul className={styles.recruiterPoints}>
                <li>Every score links to the repository, project or transcript behind it</li>
                <li>Copied and forked repositories are caught and count for nothing</li>
                <li>Fill every seat across several roles at once, without placing anyone twice</li>
              </ul>
              <Link href={hire} className="btn btn-primary">
                Start a hiring drive
              </Link>
            </div>
            <figure className={styles.shortlist} aria-label="An example shortlist">
              <figcaption>
                <strong>Data analyst intern</strong>
                <span>SQL and Python required, Defended preferred</span>
              </figcaption>
              <ol>
                {SHORTLIST.map((c) => (
                  <li key={c.handle}>
                    <span className={styles.slScore}>{c.score}</span>
                    <span className={styles.slWho}>
                      <strong>@{c.handle}</strong>
                      <span>
                        {c.note}. {c.skills}
                      </span>
                    </span>
                    <span className={c.level === "Defended" ? styles.slDefended : styles.slVerified}>
                      {c.level === "Defended" ? <SealDot /> : null}
                      {c.level}
                    </span>
                  </li>
                ))}
              </ol>
            </figure>
          </div>
        </section>
      </main>

      <footer className={styles.footer}>
        <span>© {new Date().getFullYear()} Notch</span>
        <span>Your raw chats are never stored or shown.</span>
      </footer>
    </div>
  );
}
