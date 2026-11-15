import type { Metadata } from "next";
import Link from "next/link";
import { ApiError, api } from "@/lib/server-api";
import { fileHref } from "@/lib/code";
import { plural, relativeTime, shortDate } from "@/lib/format";
import type { CodeFileSummary, ReviewDetail, ReviewIssue, ReviewReport, ReviewSeverity, ReviewSummary, SeenBefore } from "@/lib/types";
import { Pending } from "./Pending";
import { ReviewUpload } from "./ReviewUpload";
import styles from "./review.module.css";

export const metadata: Metadata = { title: "Review" };

const SEVERITY: Record<ReviewSeverity, { label: string; chip: string }> = {
  high: { label: "High", chip: "chip chip-danger" },
  medium: { label: "Medium", chip: "chip chip-warn" },
  low: { label: "Low", chip: "chip" },
};

const CATEGORY: Record<string, string> = {
  bug: "Bug",
  security: "Security",
  performance: "Performance",
  maintainability: "Maintainability",
  testing: "Testing",
  decision: "Goes against a decision",
  other: "Other",
};

const RATING: Record<string, { label: string; chip: string }> = {
  good: { label: "Good", chip: "chip chip-ok" },
  fair: { label: "Fair", chip: "chip chip-warn" },
  weak: { label: "Weak", chip: "chip chip-danger" },
};

const PRIORITY: Record<string, string> = { now: "Now", soon: "Soon", later: "Later" };

export default async function ReviewPage({
  params,
  searchParams,
}: {
  params: Promise<{ projectId: string }>;
  searchParams: Promise<{ r?: string }>;
}) {
  const { projectId } = await params;
  const { r } = await searchParams;
  const reviews = await api<ReviewSummary[]>(`/api/projects/${projectId}/reviews`);
  if (reviews.length === 0) return <Empty projectId={projectId} />;

  const selectedId = r && reviews.some((x) => x.id === r) ? r : reviews[0].id;
  let detail: ReviewDetail | null = null;
  try {
    detail = await api<ReviewDetail>(`/api/projects/${projectId}/reviews/${selectedId}`);
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 404)) throw e;
  }
  // Issues link to Explain's file view when the code map has the file.
  const mapped = new Set(
    (await api<CodeFileSummary[]>(`/api/projects/${projectId}/code-map/files`).catch(() => [] as CodeFileSummary[])).map((f) => f.path),
  );

  return (
    <div className={styles.page}>
      <section className={`${styles.stage} on-brand`} aria-labelledby="review-title">
        <p className={styles.kicker}>Code review</p>
        <h1 id="review-title" className={styles.stageTitle}>
          {detail ? detail.label : "Review"}
        </h1>
        {detail ? <p className={styles.meta}>{describe(detail)}</p> : null}
        <div className={styles.stageActions}>
          <ReviewUpload projectId={projectId} compact />
          <span className={styles.orClaude}>
            or type <code>/notch review</code> in Claude Code
          </span>
        </div>
      </section>

      <div className={styles.layout}>
        <div className={styles.main}>
          {!detail ? (
            <p className={styles.notice}>That review is gone.</p>
          ) : detail.status === "PENDING" ? (
            <Pending label={detail.label} createdAt={detail.createdAt} />
          ) : detail.status === "FAILED" || !detail.report ? (
            <section className={styles.failed} role="alert">
              <h2>The review didn&rsquo;t finish</h2>
              <p>{detail.error ?? "Something went wrong. Upload the zip again."}</p>
            </section>
          ) : (
            <Report projectId={projectId} report={detail.report} mapped={mapped} seen={detail.seenBefore ?? []} />
          )}
        </div>

        {reviews.length > 1 ? (
          <aside className={styles.history} aria-labelledby="history-title">
            <h2 id="history-title">Earlier reviews</h2>
            <ul>
              {reviews.map((x) => (
                <li key={x.id}>
                  <Link href={`/p/${projectId}/review?r=${x.id}`} aria-current={x.id === selectedId ? "page" : undefined}
                    className={styles.historyLink}>
                    <span className={styles.historyLabel}>{x.label}</span>
                    <span className={styles.historyMeta}>
                      {x.source === "claude-code" ? "Claude Code" : "Zip"} · {relativeTime(x.createdAt)}
                      {x.status === "DONE" ? ` · ${plural(x.issues, "issue")}` : x.status === "PENDING" ? " · running" : " · failed"}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          </aside>
        ) : null}
      </div>
    </div>
  );
}

function describe(d: ReviewDetail): string {
  const how = d.source === "claude-code" ? "Reviewed in Claude Code" : "Uploaded as a zip";
  return `${how} ${relativeTime(d.createdAt).toLowerCase()}.`;
}

function Report({ projectId, report, mapped, seen }: {
  projectId: string;
  report: ReviewReport;
  mapped: Set<string>;
  seen: SeenBefore[][];
}) {
  const counts = { high: 0, medium: 0, low: 0 } as Record<ReviewSeverity, number>;
  report.issues.forEach((i) => counts[i.severity]++);
  const c = report.coverage;
  const reviewer = !c ? null : c.reviewer === "automated checks" ? "Notch's automated checks" : c.reviewer;

  return (
    <>
      <section className={styles.summary} aria-labelledby="summary-title">
        <h2 id="summary-title" className="sr-only">Overall assessment</h2>
        {report.summary ? <p className={styles.summaryText}>{report.summary}</p> : null}
        <ul className={styles.counts} aria-label="Issues by severity">
          {(["high", "medium", "low"] as ReviewSeverity[]).map((s) => (
            <li key={s} className={styles.count} data-severity={s}>
              <span className={styles.countNumber}>{counts[s]}</span>
              <span>{SEVERITY[s].label}</span>
            </li>
          ))}
        </ul>
        {c ? (
          <p className={styles.coverage}>
            Reviewed by {reviewer}
            {c.filesReviewed > 0 ? ` · read ${plural(c.filesReviewed, "file")}${c.filesTotal > 0 ? ` of ${c.filesTotal}` : ""}` : ""}
            {c.linesReviewed > 0 ? ` · ${c.linesReviewed.toLocaleString("en")} lines` : ""}
          </p>
        ) : null}
        {c?.note ? <p className={styles.note}>{c.note}</p> : null}
      </section>

      <section className={styles.section} aria-labelledby="issues-title">
        <h2 id="issues-title">Issues</h2>
        {report.issues.length === 0 ? (
          <p className={styles.sectionIntro}>No issues found.</p>
        ) : (
          <ol className={styles.issues}>
            {report.issues.map((issue, n) => (
              <IssueCard key={n} projectId={projectId} issue={issue} mapped={mapped} seen={seen[n] ?? []} />
            ))}
          </ol>
        )}
      </section>

      {report.areas.length > 0 ? (
        <section className={styles.section} aria-labelledby="areas-title">
          <h2 id="areas-title">Breakdown</h2>
          <ul className={styles.areas}>
            {report.areas.map((a) => (
              <li key={a.name} className={styles.area}>
                <div className={styles.areaHead}>
                  <h3>{a.name}</h3>
                  <span className={RATING[a.rating]?.chip ?? "chip"}>{RATING[a.rating]?.label ?? a.rating}</span>
                </div>
                <p>{a.assessment}</p>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {report.strengths.length > 0 ? (
        <section className={styles.section} aria-labelledby="strengths-title">
          <h2 id="strengths-title">What&rsquo;s done well</h2>
          <ul className={styles.strengths}>
            {report.strengths.map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ul>
        </section>
      ) : null}

      {report.recommendations.length > 0 ? (
        <section className={styles.section} aria-labelledby="recs-title">
          <h2 id="recs-title">Recommendations</h2>
          <ol className={styles.recs}>
            {report.recommendations.map((rec) => (
              <li key={rec.title} className={styles.rec}>
                <span className={styles.priority} data-priority={rec.priority}>{PRIORITY[rec.priority] ?? rec.priority}</span>
                <div>
                  <h3>{rec.title}</h3>
                  {rec.detail ? <p>{rec.detail}</p> : null}
                </div>
              </li>
            ))}
          </ol>
        </section>
      ) : null}
    </>
  );
}

function IssueCard({ projectId, issue, mapped, seen }: {
  projectId: string;
  issue: ReviewIssue;
  mapped: Set<string>;
  seen: SeenBefore[];
}) {
  const where = issue.file ? `${issue.file}${issue.line ? `:${issue.line}` : ""}` : null;
  return (
    <li className={styles.issue} data-severity={issue.severity}>
      <div className={styles.issueHead}>
        <span className={SEVERITY[issue.severity].chip}>{SEVERITY[issue.severity].label}</span>
        <span className={styles.category}>{CATEGORY[issue.category] ?? issue.category}</span>
        {issue.source === "rules" ? <span className={styles.byNotch}>Notch check</span> : null}
      </div>
      <h3 className={styles.issueTitle}>{issue.title}</h3>
      {where ? (
        issue.file && mapped.has(issue.file) ? (
          <Link href={fileHref(projectId, issue.file)} className={styles.where}>
            <code>{where}</code>
          </Link>
        ) : (
          <code className={styles.where}>{where}</code>
        )
      ) : null}
      {issue.detail ? <p className={styles.detail}>{issue.detail}</p> : null}
      {issue.recommendation ? (
        <p className={styles.fix}>
          <strong>Fix:</strong> {issue.recommendation}
        </p>
      ) : null}
      {seen.length > 0 ? (
        <div className={styles.seen}>
          <p className={styles.seenHead}>
            {issue.category === "decision" ? "Where this was decided" : "Seen before in this project"}
          </p>
          <ul>
            {seen.map((s) => (
              <li key={s.conversationId}>
                <p className={styles.seenMeta}>
                  <strong>{s.conversationTitle ?? "A conversation"}</strong>, {shortDate(s.sentAt)}
                  {s.decision ? <span className={styles.seenDecision}>Decision</span> : null}
                  {s.related > 0 ? <span className={styles.seenRelated}>{plural(s.related, "related session")}</span> : null}
                </p>
                <p className={styles.seenExcerpt}>{s.excerpt}</p>
                <Link href={`/p/${projectId}/recall?q=${encodeURIComponent(s.term)}`} className={styles.seenLink}>
                  Matched <code>{s.term}</code> exactly. See every mention
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </li>
  );
}

function Empty({ projectId }: { projectId: string }) {
  return (
    <div className={styles.page}>
      <section className={`${styles.stage} on-brand`} aria-labelledby="review-title">
        <p className={styles.kicker}>Code review</p>
        <h1 id="review-title" className={styles.stageTitle}>
          Get an honest review of your code
        </h1>
        <p className={styles.lede}>
          An overall assessment, a breakdown of each part, the issues with file and line, and what to fix first. Notch also
          checks the code against the decisions you&rsquo;ve recorded.
        </p>
      </section>
      <div className={styles.emptyGrid}>
        <ReviewUpload projectId={projectId} />
        <section className={styles.cliCard} aria-labelledby="cc-title">
          <h2 id="cc-title">Or review it in Claude Code</h2>
          <p>
            In a repository set up with <code>notch init</code>, open Claude Code and type:
          </p>
          <pre className={styles.cmd}>/notch review</pre>
          <p className="muted">
            Claude Code reads the whole repository, writes the review and saves it here. <code>/notch resume</code>,{" "}
            <code>/notch why</code> and <code>/notch decisions</code> work too.
          </p>
        </section>
      </div>
    </div>
  );
}
