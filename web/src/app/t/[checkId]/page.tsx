import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Brand } from "@/components/Brand";
import { Exchanges, Verdict } from "@/components/ownership/Transcript";
import { ApiError, publicApi } from "@/lib/server-api";
import type { CheckView } from "@/lib/portfolio-types";
import common from "@/components/portfolio/portfolio.module.css";
import styles from "./t.module.css";

type Params = { checkId: string };

async function load(id: string): Promise<CheckView> {
  try {
    return await publicApi<CheckView>(`/public/ownership/${encodeURIComponent(id)}`);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 400)) notFound();
    throw e;
  }
}

export async function generateMetadata({ params }: { params: Promise<Params> }): Promise<Metadata> {
  const c = await load((await params).checkId);
  const who = c.handle ? `@${c.handle}` : "A candidate";
  return {
    title: `${who} on ${c.repo}`,
    description: c.defended
      ? `${who} explained their own code in ${c.repo} and defended it, scoring ${c.score}. Read every question and answer.`
      : `${who}'s Ownership Check on ${c.repo}.`,
  };
}

/** The public record of a finished Ownership Check: every question, answer and grade. */
export default async function Transcript({ params }: { params: Promise<Params> }) {
  const c = await load((await params).checkId);
  const who = c.handle ? `@${c.handle}` : "This candidate";
  return (
    <div>
      <header className={common.topBar}>
        <Brand href="/" />
        <nav aria-label="Main" className={common.topLinks}>
          {c.handle ? <Link href={`/u/${c.handle}`}>{who}&rsquo;s profile</Link> : null}
          <Link href="/">Make your own profile</Link>
        </nav>
      </header>
      <section className={`${styles.head} on-brand ${common.stage}`}>
        <div className={styles.inner}>
          <Verdict check={c} who={who} />
        </div>
      </section>
      <main className={styles.body}>
        <div className={styles.explain}>
          <h2 className={styles.title}>The transcript</h2>
          <p>
            Notch read {c.repo} on GitHub, picked the parts of it that together cover what the repository shows, and asked {who} to explain them
            against the clock. The answers are exactly as written. Line numbers link to the code.
          </p>
        </div>
        <Exchanges check={c} />
      </main>
    </div>
  );
}
