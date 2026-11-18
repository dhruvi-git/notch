import Link from "next/link";
import { NotchMark } from "@/components/Brand";
import styles from "./landing.module.css";

const LEVELS = [
  {
    level: "Claimed",
    className: styles.claimed,
    title: "You typed it in",
    text: "A starting point. It shows on your profile but scores nothing until work backs it.",
  },
  {
    level: "Shown",
    className: styles.shown,
    title: "There's an artifact",
    text: "A project, a repository or a write-up uses the skill, with a link anyone can open.",
  },
  {
    level: "Verified",
    className: styles.verified,
    title: "The work checks out",
    text: "Your own commits across several weeks, not a fork, and not a copy of someone else's project.",
  },
];

const STEPS = [
  {
    title: "Connect GitHub",
    text: "Notch reads your public repositories: your commits, the weeks you worked, and the languages and tools in each.",
  },
  {
    title: "Add what GitHub can't see",
    text: "Hackathons, a club you run, a weekend build. Optionally add a ChatGPT or Claude export to surface what you've been learning.",
  },
  {
    title: "Share one link",
    text: "Your skill map, your rank among peers, and the proof behind each skill, on one page.",
  },
];

/** The sample profile in the hero. Illustrative: the real one is built from a candidate's evidence. */
const SAMPLE = [
  { name: "SQL", score: 91, level: "Verified" },
  { name: "Python", score: 84, level: "Verified" },
  { name: "Power BI", score: 58, level: "Shown" },
  { name: "Statistics", score: 46, level: "Shown" },
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
            {signedIn ? "My profile" : "Sign in"}
          </Link>
        </header>

        <section className={styles.hero}>
          <div className={styles.heroText}>
            <p className={styles.kicker}>Proof of work for tech freshers</p>
            <h1 className={styles.title}>
              Show, <span className={styles.mark}>don&rsquo;t claim.</span>
            </h1>
            <p className={styles.lede}>
              Notch turns your GitHub, projects and the things you build on weekends into a portfolio where every skill carries its proof.
              Recruiters see verified work, not another resume.
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

          <figure className={styles.sample} aria-label="An example profile">
            <div className={styles.sampleHead}>
              <span className={styles.sampleAvatar} aria-hidden="true">
                AR
              </span>
              <span>
                <strong>Asha Rao</strong>
                <span className={styles.sampleSub}>@asha-r · Data analyst</span>
              </span>
              <span className="chip chip-yellow">Top 10% SQL</span>
            </div>
            <ul className={styles.sampleRows}>
              {SAMPLE.map((s) => (
                <li key={s.name}>
                  <span className={styles.sampleName}>{s.name}</span>
                  <span className={styles.sampleTrack} role="img" aria-label={`${s.score} out of 100`}>
                    <span style={{ width: `${s.score}%` }} />
                  </span>
                  <span className={s.level === "Verified" ? styles.verified : styles.shown}>{s.level}</span>
                </li>
              ))}
            </ul>
            <figcaption className={styles.sampleFoot}>An example. Every bar is backed by repositories and projects you can open.</figcaption>
          </figure>
        </section>
      </div>

      <main>
        <section id="levels" className={styles.section} aria-labelledby="levels-title">
          <h2 id="levels-title" className={styles.sectionTitle}>
            Every skill has a level
          </h2>
          <p className={styles.sectionLede}>The level depends on the evidence behind it, so nobody can type their way to a strong profile.</p>
          <ul className={styles.levels}>
            {LEVELS.map((l) => (
              <li key={l.level} className={l.level === "Verified" ? styles.levelCardStrong : styles.levelCard}>
                <span className={l.className}>{l.level}</span>
                <h3>{l.title}</h3>
                <p>{l.text}</p>
              </li>
            ))}
          </ul>
        </section>

        <section id="students" className={styles.section} aria-labelledby="students-title">
          <h2 id="students-title" className={styles.sectionTitle}>
            Three minutes to a profile
          </h2>
          <ol className={styles.steps}>
            {STEPS.map((s, i) => (
              <li key={s.title}>
                <span className={styles.stepNum}>0{i + 1}</span>
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
                Hiring freshers? Match on proof.
              </h2>
              <p className={styles.sectionLede}>
                Paste a job description. Notch reads the skills it asks for, ranks candidates on verified evidence, and shows why each one
                scored as they did.
              </p>
              <Link href={hire} className="btn btn-primary">
                Start a drive
              </Link>
            </div>
            <ul className={styles.recruiterPoints}>
              <li>A ranked shortlist with the score broken down by skill</li>
              <li>Every claim links to the repository or project behind it</li>
              <li>Fill every seat across several roles at once</li>
            </ul>
          </div>
        </section>
      </main>

      <footer className={styles.footer}>
        <span>Notch · CIA 3, Advanced Algorithm</span>
        <span>Your raw chats are never stored or shown.</span>
      </footer>
    </div>
  );
}
