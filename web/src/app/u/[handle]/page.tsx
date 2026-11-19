import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Brand } from "@/components/Brand";
import { Seal } from "@/components/Seal";
import { Constellation, ConstellationKey } from "@/components/read/Constellation";
import type { Read } from "@/lib/read-types";
import { SkillBars } from "@/components/portfolio/SkillBars";
import { ApiError, publicApi } from "@/lib/server-api";
import { shortDate } from "@/lib/format";
import type { EvidenceView, ProfileView } from "@/lib/portfolio-types";
import common from "@/components/portfolio/portfolio.module.css";
import { ShareCard } from "./ShareCard";
import styles from "./profile.module.css";

type Params = { handle: string };

async function load(handle: string): Promise<ProfileView> {
  try {
    return await publicApi<ProfileView>(`/public/profiles/${encodeURIComponent(handle)}`);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) notFound();
    throw e;
  }
}

/** The public read (archetype and constellation); null if the API predates it. */
async function loadRead(handle: string): Promise<Read | null> {
  try {
    return await publicApi<Read>(`/public/profiles/${encodeURIComponent(handle)}/read`);
  } catch {
    return null;
  }
}

export async function generateMetadata({ params }: { params: Promise<Params> }): Promise<Metadata> {
  const { handle } = await params;
  return { title: `@${handle}`, description: `Skills and proof of work for @${handle} on Notch.` };
}

const KIND: Record<EvidenceView["kind"], string> = {
  GITHUB_REPO: "Repository",
  PROJECT: "Project",
  CHAT_EXPORT: "Learning, from AI chats",
};

function evidenceStats(e: EvidenceView): string {
  if (e.kind === "GITHUB_REPO") {
    const parts = [`${e.ownCommits} own commit${e.ownCommits === 1 ? "" : "s"}`, `${e.activeWeeks} active week${e.activeWeeks === 1 ? "" : "s"}`];
    return `${parts.join(" · ")} · last active ${shortDate(e.lastActive)}`;
  }
  return `Last active ${shortDate(e.lastActive)}`;
}

export default async function PublicProfile({ params }: { params: Promise<Params> }) {
  const { handle } = await params;
  const [p, read] = await Promise.all([load(handle), loadRead(handle)]);
  const verified = p.skills.filter((s) => s.level === "VERIFIED" || s.level === "DEFENDED").length;
  const defended = p.evidence.filter((e) => e.ownership?.defended);
  // Defended repositories first, then the rest as the API ordered them (most recent first).
  const evidence = [...p.evidence].sort((a, b) => Number(Boolean(b.ownership?.defended)) - Number(Boolean(a.ownership?.defended)));
  const commits = p.evidence.filter((e) => e.kind === "GITHUB_REPO" && !e.duplicate).reduce((n, e) => n + e.ownCommits, 0);
  const lastActive = p.evidence.map((e) => e.lastActive).sort().at(-1) ?? null;
  const badges = p.skills.filter((s) => s.topPercent).sort((a, b) => (a.topPercent ?? 100) - (b.topPercent ?? 100)).slice(0, 3);
  const best = badges[0] ?? p.skills[0];
  const name = p.handle ?? handle;

  return (
    <div className={styles.page}>
      <header className={`${common.topBar} ${common.topBarDark} on-brand`}>
        <Brand href="/" />
        <nav aria-label="Main" className={common.topLinks}>
          <Link href="/">Make your own profile</Link>
        </nav>
      </header>

      <section className={`${styles.hero} on-brand ${common.stage}`} aria-labelledby="who">
        <div className={styles.heroGrid}>
          <div className={styles.heroText}>
            <h1 id="who" className={styles.handle}>
              @{name}
            </h1>
            {read && read.archetype.key !== "EXPLORER" ? (
              <p className={styles.archetype}>
                {read.archetype.name}
                {read.archetype.trait ? <span className={styles.trait}>{read.archetype.trait.name}</span> : null}
              </p>
            ) : null}
            {p.headline ? <p className={styles.headline}>{p.headline}</p> : null}
            {badges.length > 0 ? (
              <div className={styles.badges}>
                {badges.map((s) => (
                  <span key={s.id} className={common.band}>
                    Top {s.topPercent}% in {s.name}
                  </span>
                ))}
              </div>
            ) : null}
            {defended.length > 0 ? (
              <ul className={styles.seals} aria-label="Defended repositories">
                {defended.slice(0, 3).map((e) => (
                  <li key={e.id}>
                    <a href={`/t/${e.ownership!.checkId}`} className={styles.sealLink}>
                      <Seal score={e.ownership!.score} size={84} tone="paper" label={`${e.title}: defended, score ${e.ownership!.score}. Read the transcript.`} />
                      <span>{e.title}</span>
                    </a>
                  </li>
                ))}
              </ul>
            ) : null}
            <dl className={styles.heroStats}>
              <div>
                <dt>skills backed by own code</dt>
                <dd>{verified}</dd>
              </div>
              <div>
                <dt>own commits</dt>
                <dd>{commits.toLocaleString("en-IN")}</dd>
              </div>
              {read?.stats.activeWeeks ? (
                <div>
                  <dt>weeks shipped</dt>
                  <dd>{read.stats.activeWeeks}</dd>
                </div>
              ) : null}
              <div>
                <dt>last active</dt>
                <dd>{lastActive ? shortDate(lastActive) : "\u2014"}</dd>
              </div>
            </dl>
          </div>
          {read && read.constellation.stars.length > 0 ? (
            <div className={styles.sky}>
              <Constellation stars={read.constellation.stars} links={read.constellation.links} />
              <ConstellationKey className={styles.key} />
            </div>
          ) : null}
        </div>
      </section>

      <main className={`${common.container} ${styles.layout}`}>
        <section aria-labelledby="skills" className={styles.skills}>
          <h2 id="skills" className={styles.sectionTitle}>
            Skills
          </h2>
          <p className={`${common.muted} ${common.small}`}>
            Scores run from 0 to 100 and come only from the evidence below. The notch marks 85, the most one repository earns a skill on its own;
            past it means several repositories, or one its owner defended.
          </p>
          {p.skills.length > 0 ? (
            <SkillBars skills={p.skills} linkToEvidence />
          ) : (
            <p className={common.muted}>No skills yet.</p>
          )}
          {p.poolSize > 0 ? (
            <p className={`${common.muted} ${common.small} ${styles.note}`}>
              Ranks compare @{name} with the {p.poolSize} public profiles on Notch that have evidence for the same skill.
            </p>
          ) : null}
        </section>

        {best ? (
          <aside aria-labelledby="share" className={styles.aside}>
            <h2 id="share" className={common.eyebrow}>
              Share card
            </h2>
            <ShareCard
              handle={name}
              headline={defended[0] ? "Defended" : best.topPercent ? `Top ${best.topPercent}%` : `${Math.round(best.score)}/100`}
              subline={
                defended[0]
                  ? `my own code in ${defended[0].title}, scoring ${defended[0].ownership!.score}`
                  : `in ${best.name}, from ${best.level === "VERIFIED" || best.level === "DEFENDED" ? "verified" : "real"} work`
              }
              detail={commits > 0 ? `${commits} own commits` : `${p.evidence.length} pieces of evidence`}
            />
          </aside>
        ) : null}

        <section aria-labelledby="proof" className={styles.proof}>
          <h2 id="proof" className={styles.sectionTitle}>
            Proof of work
          </h2>
          <ul className={styles.record}>
            {evidence.map((e) => {
              const own = e.ownership;
              return (
                <li key={e.id} id={`evidence-${e.id}`} className={`${styles.entry} ${own?.defended ? styles.entryDefended : ""}`}>
                  <div className={styles.entryMain}>
                    <p className={styles.kind}>{KIND[e.kind]}</p>
                    <h3 className={styles.cardTitle}>
                      {e.url ? (
                        <a href={e.url} rel="noopener noreferrer" target="_blank">
                          {e.title}
                        </a>
                      ) : (
                        e.title
                      )}
                    </h3>
                    {e.description ? <p className={`${common.muted} ${styles.desc}`}>{e.description}</p> : null}
                    <p className={styles.statsLine}>{evidenceStats(e)}</p>
                    {e.duplicate ? <p className={common.notice}>Matches a repository another person claimed first, so it doesn&rsquo;t count.</p> : null}
                    <div className={common.chips}>
                      {e.skills.map((s) => (
                        <span key={s.id} className={common.tag}>
                          {s.name}
                        </span>
                      ))}
                    </div>
                  </div>
                  {own?.defended ? (
                    <a href={`/t/${own.checkId}`} className={styles.entrySeal}>
                      <Seal score={own.score} size={104} label={`Defended, score ${own.score}`} />
                      <span>
                        Defended {shortDate(own.finishedAt)}
                        <strong>Read the transcript</strong>
                      </span>
                    </a>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </section>
      </main>
    </div>
  );
}
