import type { Metadata } from "next";
import Link from "next/link";
import { ApiError, api } from "@/lib/server-api";
import { plural, relativeTime } from "@/lib/format";
import type {
  CodeOverview, ConversationSummary, Health, HealthRisk, Project, RecapItem, ResumeView, ReviewDetail, ReviewSummary,
} from "@/lib/types";
import styles from "./overview.module.css";

export const metadata: Metadata = { title: "Overview" };

async function optional<T>(path: string): Promise<T | null> {
  try {
    return await api<T>(path);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.code === "no_code_map")) return null;
    throw e;
  }
}

/**
 * A project's home: what to do next, and the state of its code and memory at a glance. Every
 * part links to the page that has the detail.
 */
export default async function Overview({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  const p = `/api/projects/${projectId}`;
  const [project, reviews, map, resume, conversations, health] = await Promise.all([
    api<Project>(p),
    api<ReviewSummary[]>(`${p}/reviews`),
    optional<CodeOverview>(`${p}/code-map`),
    api<ResumeView>(`${p}/resume`),
    api<ConversationSummary[]>(`${p}/conversations`),
    api<Health>(`${p}/health`),
  ]);
  const latestDone = reviews.find((r) => r.status === "DONE");
  const running = reviews.find((r) => r.status === "PENDING");
  const review = latestDone ? await optional<ReviewDetail>(`${p}/reviews/${latestDone.id}`) : null;
  const base = `/p/${projectId}`;

  const hasCode = Boolean(map) || reviews.length > 0;
  const connected = conversations.some((c) => (c.source as string) === "claude-code" || (c.source as string) === "git");
  const hasChats = conversations.some((c) => ["chatgpt", "claude", "markdown", "text"].includes(c.source));

  const steps = [
    {
      done: hasCode,
      title: "Add your code",
      text: "Choose the project folder. Notch reviews it and maps how it works.",
      href: `${base}/imports`,
      action: "Upload code",
    },
    {
      done: Boolean(latestDone),
      title: "Read the review",
      text: running ? "A review is running now." : "The overall assessment, the issues by file and line, and what to fix first.",
      href: `${base}/review`,
      action: "Open Review",
    },
    {
      done: connected,
      title: "Connect the repository",
      text: "Run notch init once, and every Claude Code session and commit adds to this project by itself.",
      href: `${base}/sync`,
      action: "Connect repo",
    },
    {
      done: hasChats,
      optional: true,
      title: "Add past chats",
      text: "A ChatGPT or Claude export brings in decisions you made before Notch.",
      href: `${base}/imports`,
      action: "Add chats",
    },
  ];
  const remaining = steps.filter((s) => !s.done && !s.optional).length;

  return (
    <div className={styles.page}>
      <section className={`${styles.stage} on-brand`} aria-labelledby="overview-title">
        <h1 id="overview-title" className={styles.title}>
          {project.name}
        </h1>
        <p className={styles.status}>{statusLine(latestDone, running, map, resume)}</p>
      </section>

      {remaining > 0 ? (
        <section className={styles.setup} aria-labelledby="setup-title">
          <h2 id="setup-title">{remaining === 1 ? "One step left" : `${remaining} steps to get the most out of Notch`}</h2>
          <ol className={styles.steps}>
            {steps.map((s) => (
              <li key={s.title} className={styles.step} data-done={s.done}>
                <span className={styles.tick} aria-hidden="true">{s.done ? "✓" : ""}</span>
                <div className={styles.stepBody}>
                  <h3>
                    {s.title}
                    {s.optional ? <span className={styles.optional}>optional</span> : null}
                    {s.done ? <span className="sr-only"> (done)</span> : null}
                  </h3>
                  {!s.done ? <p>{s.text}</p> : null}
                </div>
                {!s.done ? (
                  <Link href={s.href} className={s.optional ? "btn btn-quiet" : "btn btn-primary"}>
                    {s.action}
                  </Link>
                ) : null}
              </li>
            ))}
          </ol>
        </section>
      ) : null}

      <Recap recap={health.recap} />

      <section className={styles.health} aria-labelledby="health-title">
        <h2 id="health-title">Health check</h2>
        <p className={styles.healthIntro}>
          The ways projects built with AI agents fall apart, checked against this one. Each comes with the evidence and one
          thing to do.
        </p>
        <ul className={styles.risks}>
          {health.risks.map((r) => (
            <RiskRow key={r.key} risk={r} base={base} />
          ))}
        </ul>
      </section>

      <h2 className={styles.glanceTitle}>At a glance</h2>
      <div className={styles.grid}>
        <section className={`${styles.panel} ${styles.wide}`} aria-labelledby="review-title">
          <div className={styles.panelHead}>
            <h2 id="review-title">Code review</h2>
            <Link href={`${base}/review`}>{review ? "Open the review" : "Review your code"}</Link>
          </div>
          {review?.report ? (
            <>
              <p className={styles.counts}>
                <span className={styles.count} data-severity="high"><strong>{count(review, "high")}</strong> High</span>
                <span className={styles.count} data-severity="medium"><strong>{count(review, "medium")}</strong> Medium</span>
                <span className={styles.count}><strong>{count(review, "low")}</strong> Low</span>
              </p>
              {review.report.summary ? <p className={styles.summary}>{firstSentences(review.report.summary, 2)}</p> : null}
              <p className={styles.meta}>
                {review.label}, reviewed {relativeTime(review.createdAt).toLowerCase()}
                {review.source === "claude-code" ? " in Claude Code" : ""}.
              </p>
            </>
          ) : running ? (
            <p className={styles.empty}>Reviewing {running.label} now. It usually takes a minute or two.</p>
          ) : (
            <p className={styles.empty}>
              No review yet. <Link href={`${base}/imports`}>Upload your code</Link> or type <code>/notch review</code> in
              Claude Code.
            </p>
          )}
        </section>

        <section className={styles.panel} aria-labelledby="map-title">
          <div className={styles.panelHead}>
            <h2 id="map-title">How it&rsquo;s built</h2>
            <Link href={`${base}/explain`}>Explain</Link>
          </div>
          {map ? (
            <>
              <p className={styles.big}>{plural(map.stats.codeFiles, "code file")}</p>
              <p className={styles.meta}>
                {map.stats.lines.toLocaleString("en")} lines in {plural(map.modules.length, "module")}
                {topLanguages(map) ? `, mostly ${topLanguages(map)}` : ""}.
              </p>
              {map.startHere.length > 0 ? (
                <p className={styles.small}>
                  Start reading at <code>{map.startHere[0].path}</code>.
                </p>
              ) : null}
            </>
          ) : (
            <p className={styles.empty}>The map appears once you&rsquo;ve added your code.</p>
          )}
        </section>

        <section className={styles.panel} aria-labelledby="stopped-title">
          <div className={styles.panelHead}>
            <h2 id="stopped-title">Where you stopped</h2>
            <Link href={`${base}/resume`}>See all</Link>
          </div>
          {resume.lastSession || resume.openTasks.length > 0 ? (
            <>
              {resume.lastSession ? (
                <p className={styles.small}>
                  Last session: <strong>{resume.lastSession.title}</strong>, {relativeTime(resume.lastSession.lastActivity).toLowerCase()}.
                </p>
              ) : null}
              {resume.openTasks.length > 0 ? (
                <ul className={styles.list}>
                  {resume.openTasks.slice(0, 3).map((t) => (
                    <li key={t.id}>{t.title}</li>
                  ))}
                </ul>
              ) : null}
            </>
          ) : (
            <p className={styles.empty}>Connect the repository and your last session and open tasks show up here.</p>
          )}
        </section>

        <section className={styles.panel} aria-labelledby="decisions-title">
          <div className={styles.panelHead}>
            <h2 id="decisions-title">Decisions</h2>
            <Link href={`${base}/ledger`}>Ledger</Link>
          </div>
          {resume.decisions.length > 0 ? (
            <ul className={styles.list}>
              {resume.decisions.slice(0, 3).map((d) => (
                <li key={d.id}>{d.title}</li>
              ))}
            </ul>
          ) : (
            <p className={styles.empty}>Decisions are collected from your sessions, commits and chats as you work.</p>
          )}
          {resume.driftAlerts > 0 ? (
            <p className={styles.drift}>
              <Link href={`${base}/drift`}>
                {resume.driftAlerts === 1 ? "1 thing goes" : `${resume.driftAlerts} things go`} against a decision
              </Link>
            </p>
          ) : null}
        </section>
      </div>
    </div>
  );
}

const LEVEL: Record<string, string> = { ok: "Fine", watch: "Worth a look", risk: "At risk", none: "Not enough yet" };

function RiskRow({ risk, base }: { risk: HealthRisk; base: string }) {
  return (
    <li className={styles.risk} data-level={risk.level}>
      <div className={styles.riskHead}>
        <span className={styles.level} data-level={risk.level}>{LEVEL[risk.level] ?? risk.level}</span>
        <h3>{risk.title}</h3>
      </div>
      <p className={styles.riskHeadline}>{risk.headline}</p>
      {risk.evidence.length > 0 ? (
        <ul className={styles.evidence}>
          {risk.evidence.map((e) => (
            <li key={e.text}>
              {e.link ? <Link href={`${base}/${e.link}`}>{e.text}</Link> : <span>{e.text}</span>}
              {e.detail ? <span className={styles.evidenceDetail}> {e.detail}</span> : null}
            </li>
          ))}
        </ul>
      ) : null}
      {risk.level !== "ok" ? (
        <Link href={`${base}/${risk.actionLink}`} className={styles.riskAction}>
          {risk.action}
        </Link>
      ) : null}
    </li>
  );
}

/** The second brain: what's done, what isn't, and what's been forgotten, for coming back to a project. */
function Recap({ recap }: { recap: Health["recap"] }) {
  if (recap.done.length + recap.open.length + recap.forgotten.length === 0) return null;
  const column = (title: string, items: RecapItem[], empty: string, tone: string) => (
    <div className={styles.recapCol} data-tone={tone}>
      <h3>{title}</h3>
      {items.length === 0 ? (
        <p className={styles.empty}>{empty}</p>
      ) : (
        <ul>
          {items.map((i, n) => (
            <li key={n}>
              <span>{i.text}</span>
              <span className={styles.recapMeta}>
                {i.detail ? `${i.detail}, ` : ""}
                {relativeTime(i.when).toLowerCase()}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
  return (
    <section className={styles.recap} aria-labelledby="recap-title">
      <h2 id="recap-title">Where things stand</h2>
      <div className={styles.recapGrid}>
        {column("Done recently", recap.done, "Nothing finished in the last three weeks.", "done")}
        {column("Still to do", recap.open, "No recent open tasks.", "open")}
        {column("You may have forgotten", recap.forgotten, "Nothing left hanging.", "forgotten")}
      </div>
    </section>
  );
}

function count(review: ReviewDetail, severity: string) {
  return review.report?.issues.filter((i) => i.severity === severity).length ?? 0;
}

/** The first n sentences. A sentence ends at . ! or ? followed by a space, so "Next.js" stays whole. */
function firstSentences(text: string, n: number) {
  return text.split(/(?<=[.!?])\s+/).slice(0, n).join(" ").trim();
}

function topLanguages(map: CodeOverview) {
  return Object.entries(map.stats.languages).sort((a, b) => b[1] - a[1]).slice(0, 2).map(([l]) => l).join(" and ");
}

function statusLine(done: ReviewSummary | undefined, running: ReviewSummary | undefined, map: CodeOverview | null,
                    resume: ResumeView): string {
  if (running) return `Reviewing ${running.label} now.`;
  if (done) {
    const high = done.highIssues;
    return `Last reviewed ${relativeTime(done.createdAt).toLowerCase()}: ${plural(done.issues, "issue")}${high ? `, ${high} high` : ""}.`
      + (resume.openTasks.length ? ` ${plural(resume.openTasks.length, "open task")}.` : "");
  }
  if (map) return "Your code is mapped. Review it to see the issues and what to fix first.";
  return "Start by adding your code. Everything else builds on it.";
}
