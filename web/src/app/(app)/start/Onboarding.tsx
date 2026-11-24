"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Seal } from "@/components/Seal";
import { SkillPicker } from "@/components/portfolio/SkillPicker";
import { call, ClientApiError, postJson } from "@/lib/client-api";
import type { ChatImportResult, CheckView, EvidenceView, GitHubSyncResult, ProfileView, SkillRef } from "@/lib/portfolio-types";
import type { User } from "@/lib/types";
import common from "@/components/portfolio/portfolio.module.css";
import styles from "./onboarding.module.css";

export function message(err: unknown, fallback: string): string {
  return err instanceof ClientApiError ? err.message : fallback;
}

/** The candidate's home: connect GitHub, add chats and projects, approve skills, publish. */
export function GitHubSection({ user, hasRepos, onDone }: { user: User; hasRepos: boolean; onDone: () => void }) {
  const [username, setUsername] = useState(user.github ? user.handle : "");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<GitHubSyncResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function sync(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await postJson<GitHubSyncResult>("/portfolio/github/sync", { username: username.trim() || null });
      setResult(r);
      onDone();
    } catch (err) {
      setError(message(err, "The sync didn't finish. Try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.pane}>
      <p className={`${common.muted} ${common.small}`}>
        Notch reads your public repositories: your own commits, the weeks you worked, and the languages and tools in each. Forks and
        repositories without your commits are left out.
      </p>
      <form className={styles.inline} onSubmit={sync}>
        <label htmlFor="gh-user" className={styles.inlineLabel}>
          GitHub username
        </label>
        <input id="gh-user" className="field" value={username} onChange={(e) => setUsername(e.target.value)} placeholder="your-username" maxLength={39} />
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? "Reading your repositories…" : hasRepos ? "Sync again" : "Connect"}
        </button>
      </form>
      {error ? (
        <p className={common.error} role="alert">
          {error}
        </p>
      ) : null}
      {result ? (
        <div className={styles.result} role="status">
          <div className={common.stats}>
            <div>
              <div className={common.statValue}>{result.added + result.updated}</div>
              <div className={common.statLabel}>repositories counted</div>
            </div>
            <div>
              <div className={common.statValue}>{result.forksSkipped}</div>
              <div className={common.statLabel}>forks skipped</div>
            </div>
            <div>
              <div className={common.statValue}>{result.withoutOwnCommits}</div>
              <div className={common.statLabel}>without your commits</div>
            </div>
          </div>
          {result.duplicates.length > 0 ? (
            <p className={common.notice}>
              {result.duplicates.join(", ")} {result.duplicates.length === 1 ? "matches a repository" : "match repositories"} someone else
              claimed first, so {result.duplicates.length === 1 ? "it doesn't" : "they don't"} count.
            </p>
          ) : null}
          {result.note ? <p className={`${common.muted} ${common.small}`}>{result.note}</p> : null}
        </div>
      ) : null}
    </div>
  );
}

export function ChatSection({ onDone }: { onDone: () => void }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ChatImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function upload(file: File) {
    setBusy(true);
    setError(null);
    try {
      const body = new FormData();
      body.append("file", file);
      const r = await call<ChatImportResult>("/portfolio/chat-export", { method: "POST", body });
      setResult(r);
      onDone();
    } catch (err) {
      setError(message(err, "The export couldn't be read. Upload conversations.json or the export zip."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.pane}>
      <p className={`${common.muted} ${common.small}`}>
        Upload your ChatGPT or Claude export to surface what you&rsquo;ve been learning. Only your own messages are read, the file
        isn&rsquo;t kept, and nobody sees your chats. Suggestions count only after you approve them.
      </p>
      <label className={styles.drop}>
        <span className={styles.dropTitle}>{busy ? "Reading your export…" : "Choose conversations.json or the export zip"}</span>
        <span className={`${common.muted} ${common.small}`}>ChatGPT: Settings › Data controls › Export. Claude: Settings › Privacy › Export data.</span>
        <input
          type="file"
          accept=".json,.zip"
          className={styles.fileInput}
          disabled={busy}
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) upload(f);
            e.target.value = "";
          }}
        />
      </label>
      {error ? (
        <p className={common.error} role="alert">
          {error}
        </p>
      ) : null}
      {result ? (
        <p className={common.notice} role="status">
          Read {result.conversations} conversations and found {result.suggestions.length} skill
          {result.suggestions.length === 1 ? "" : "s"} you kept coming back to. Approve them below.
        </p>
      ) : null}
    </div>
  );
}

export function ProjectSection({ onDone }: { onDone: () => void }) {
  const [title, setTitle] = useState("");
  const [url, setUrl] = useState("");
  const [description, setDescription] = useState("");
  const [skills, setSkills] = useState<SkillRef[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim()) {
      setError("Give the project a title.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await postJson("/portfolio/evidence", {
        title: title.trim(),
        url: url.trim() || null,
        description: description.trim() || null,
        skills: skills.map((s) => s.name),
      });
      setSaved(title.trim());
      setTitle("");
      setUrl("");
      setDescription("");
      setSkills([]);
      onDone();
    } catch (err) {
      setError(message(err, "The project wasn't saved. Try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.pane}>
      <p className={`${common.muted} ${common.small}`}>
        A hackathon, a club you run, a weekend build: anything GitHub can&rsquo;t see. Projects count as shown evidence.
      </p>
      <form className={styles.form} onSubmit={submit} noValidate>
        <label className={styles.field}>
          <span>Title</span>
          <input className="field" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} placeholder="College fest ticket analytics" />
        </label>
        <label className={styles.field}>
          <span>Link (optional)</span>
          <input className="field" value={url} onChange={(e) => setUrl(e.target.value)} maxLength={500} placeholder="https://" inputMode="url" />
        </label>
        <label className={styles.field}>
          <span>What you did (optional)</span>
          <textarea className="field" rows={3} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} />
        </label>
        <SkillPicker label="Skills it shows" onPick={(s) => setSkills((cur) => (cur.some((c) => c.id === s.id) ? cur : [...cur, s]))} exclude={skills.map((s) => s.id)} />
        {skills.length > 0 ? (
          <ul className={styles.picked} aria-label="Chosen skills">
            {skills.map((s) => (
              <li key={s.id}>
                <span className={common.tag}>{s.name}</span>
                <button type="button" className={styles.remove} aria-label={`Remove ${s.name}`} onClick={() => setSkills((cur) => cur.filter((c) => c.id !== s.id))}>
                  ×
                </button>
              </li>
            ))}
          </ul>
        ) : null}
        <div>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? "Saving…" : "Add project"}
          </button>
        </div>
        {error ? (
          <p className={common.error} role="alert">
            {error}
          </p>
        ) : null}
        {saved ? (
          <p className={common.notice} role="status">
            Added {saved}.
          </p>
        ) : null}
      </form>
    </div>
  );
}

/** Everything an import suggested, waiting for the candidate: nothing counts until they say so. */
export function ReviewSection({ profile, onDone }: { profile: ProfileView; onDone: () => void }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const projects = profile.evidence.filter((e) => e.kind === "PROJECT" && e.pendingSkills.length > 0);
  // One row per skill, however many sources suggested it (AI memory and a chat export can both).
  const bySkill = new Map<number, { id: number; name: string; sources: EvidenceView[] }>();
  for (const e of profile.evidence) {
    if (e.kind !== "CHAT_EXPORT") continue;
    for (const s of e.pendingSkills) {
      const row = bySkill.get(s.id) ?? { id: s.id, name: s.name, sources: [] };
      row.sources.push(e);
      bySkill.set(s.id, row);
    }
  }
  const skills = [...bySkill.values()];
  type Row = { key: string; title: string; detail: string; evidence: EvidenceView[]; project: boolean };
  const rows: Row[] = [
    ...projects.map((e) => ({
      key: e.id,
      title: e.title,
      detail: `Project${e.description ? `: ${e.description}` : ""}${e.pendingSkills.length ? ` (${e.pendingSkills.map((s) => s.name).join(", ")})` : ""}`,
      evidence: [e],
      project: true,
    })),
    ...skills.map((k) => ({
      key: `skill-${k.id}`,
      title: k.name,
      detail: k.sources.map((e) => sourceText(e, k.name)).join("; "),
      evidence: k.sources,
      project: false,
    })),
  ];
  if (rows.length === 0) return null;
  const SHOWN = 4;
  const visible = open ? rows : rows.slice(0, SHOWN);

  async function run(key: string, evidence: EvidenceView[], keep: boolean) {
    setBusy(key);
    setError(null);
    try {
      for (const e of evidence) {
        if (keep) for (const s of e.pendingSkills) await postJson(`/portfolio/evidence/${e.id}/skills/${s.id}`, { approved: true }, "PATCH");
        else await call(`/portfolio/evidence/${e.id}`, { method: "DELETE" });
      }
      onDone();
    } catch (err) {
      setError(message(err, "That didn't save. Try again."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className={styles.review} aria-labelledby="review">
      <div className={styles.reviewHead}>
        <div>
          <h2 id="review" className={common.cardTitle}>
            {rows.length === 1 ? "1 thing to review" : `${rows.length} things to review`}
          </h2>
          <p className={`${common.muted} ${common.small}`}>
            From your AI history. They already shape your read; add them to your profile and they count towards your scores too.
          </p>
        </div>
        {rows.length > 1 ? (
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy !== null}
            onClick={() => run("all", rows.flatMap((r) => r.evidence), true)}
          >
            {busy === "all" ? "Adding\u2026" : "Add all"}
          </button>
        ) : null}
      </div>
      <ul className={styles.suggestions}>
        {visible.map((r) => (
          <li key={r.key} className={styles.suggestion}>
            <div className={styles.suggestionText}>
              <strong>{r.title}</strong>
              <span className={`${common.muted} ${common.small}`}>{r.detail}</span>
            </div>
            <button type="button" className="btn btn-quiet" disabled={busy !== null} onClick={() => run(r.key, r.evidence, false)}>
              {r.project ? "Not mine" : "Not me"}
            </button>
            <button type="button" className="btn btn-primary" disabled={busy !== null} onClick={() => run(r.key, r.evidence, true)}>
              {r.project ? "Add project" : "Add"}
            </button>
          </li>
        ))}
      </ul>
      {rows.length > SHOWN ? (
        <button type="button" className={styles.more} onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          {open ? "Show fewer" : `Show ${rows.length - SHOWN} more`}
        </button>
      ) : null}
      {error ? (
        <p className={common.error} role="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}

/** "learning, from AI memory" / "came up in 14 ChatGPT conversations", from the evidence title. */
function sourceText(e: EvidenceView, skill: string) {
  if (e.title.startsWith(`${skill}: `)) {
    const t = e.title.slice(skill.length + 2);
    return t.charAt(0).toUpperCase() + t.slice(1);
  }
  const m = /^Discussed .+ in (\d+) (.+) conversations$/.exec(e.title);
  return m ? `Came up in ${m[1]} ${m[2]} conversations` : e.title;
}

export function PublishSection({ profile, onDone }: { profile: ProfileView; onDone: () => void }) {
  const [handle, setHandle] = useState(profile.handle ?? "");
  const [headline, setHeadline] = useState(profile.headline ?? "");
  const [isPublic, setPublic] = useState(profile.hasProfile ? profile.isPublic : true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await postJson("/portfolio/me", { handle: handle.trim().toLowerCase(), headline: headline.trim() || null, domain: null, isPublic }, "PUT");
      setSaved(true);
      onDone();
    } catch (err) {
      setError(message(err, "Your profile wasn't saved. Try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={common.card} aria-labelledby="publish">
      <h2 id="publish" className={common.cardTitle}>
        Publish
      </h2>
      <form className={styles.form} onSubmit={submit} noValidate>
        <label className={styles.field}>
          <span>Your link</span>
          <span className={styles.prefixed}>
            <span className={styles.prefix}>/u/</span>
            <input className="field" value={handle} onChange={(e) => setHandle(e.target.value)} maxLength={39} placeholder="your-name" autoCapitalize="none" spellCheck={false} />
          </span>
        </label>
        <label className={styles.field}>
          <span>Headline</span>
          <input className="field" value={headline} onChange={(e) => setHeadline(e.target.value)} maxLength={120} placeholder="Data analyst · final-year B.Tech" />
        </label>
        <label className={styles.check}>
          <input type="checkbox" checked={isPublic} onChange={(e) => setPublic(e.target.checked)} />
          <span>Public: recruiters can find me and anyone with the link can see my profile</span>
        </label>
        <div className={styles.actions}>
          <button type="submit" className="btn btn-yellow" disabled={busy || !handle.trim()}>
            {busy ? "Saving…" : profile.hasProfile ? "Save" : "Publish my profile"}
          </button>
          {profile.handle && profile.isPublic ? (
            <Link href={`/u/${profile.handle}`} className="btn btn-quiet">
              View my profile
            </Link>
          ) : null}
        </div>
        {error ? (
          <p className={common.error} role="alert">
            {error}
          </p>
        ) : null}
        {saved ? (
          <p className={common.notice} role="status">
            Saved.
          </p>
        ) : null}
      </form>
    </section>
  );
}

const RETAKE_HOURS = 24;

/** Ownership Checks: explain your own code against the clock, and the repository becomes Defended. */
export function DefendSection({ profile }: { profile: ProfileView }) {
  const repos = profile.evidence.filter((e) => e.kind === "GITHUB_REPO" && !e.duplicate);
  const defended = repos.filter((e) => e.ownership?.defended).length;
  return (
    <section className={`${common.card} ${styles.defend}`} aria-labelledby="defend">
      <div className={styles.defendHead}>
        <div>
          <h2 id="defend" className={common.cardTitle}>
            Defend your code
          </h2>
          <p className={`${common.muted} ${common.small}`}>
            Anyone can push a repository. Explain yours and it becomes Defended: the highest level on Notch, and the only kind of repository that
            can take a skill past 85 on its own. Recruiters can read every question and answer.
          </p>
        </div>
        <p className={styles.defendCount}>
          <strong>{defended}</strong> of {repos.length} defended
        </p>
      </div>
      <ul className={styles.defendList}>
        {repos.map((e) => (
          <DefendRow key={e.id} repo={e} />
        ))}
      </ul>
    </section>
  );
}

/** What happens while a check is prepared (OwnershipService.create), with roughly when each stage starts. */
const PREP_STEPS: { label: string; at: number }[] = [
  { label: "Fetching your source files from GitHub", at: 0 },
  { label: "Splitting them into functions, classes and queries", at: 3500 },
  { label: "Choosing the parts that best cover your skills", at: 7000 },
  { label: "Writing five questions about your code", at: 10000 },
];

/**
 * The 10–30 seconds before a check starts. The server answers once, at the end, so stages
 * advance on a timer that matches their usual timing; the last one stays active until the
 * questions arrive.
 */
function PrepSteps() {
  const [now, setNow] = useState(0);
  useEffect(() => {
    const start = Date.now();
    const t = setInterval(() => setNow(Date.now() - start), 250);
    return () => clearInterval(t);
  }, []);
  const current = PREP_STEPS.reduce((c, s, i) => (now >= s.at ? i : c), 0);
  return (
    <ol className={styles.prep} aria-live="polite">
      {PREP_STEPS.map((s, i) => (
        <li key={s.label} className={i < current ? styles.prepDone : i === current ? styles.prepNow : styles.prepNext}>
          <span className={styles.prepDot} aria-hidden="true" />
          {s.label}
          {i === current ? <span className="sr-only"> (in progress)</span> : null}
        </li>
      ))}
    </ol>
  );
}

function DefendRow({ repo }: { repo: EvidenceView }) {
  const router = useRouter();
  const [briefing, setBriefing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const own = repo.ownership;
  const retakeAt = own && !own.defended ? Date.parse(own.finishedAt) + RETAKE_HOURS * 3_600_000 : 0;
  const waiting = retakeAt > Date.now();

  async function start() {
    setBusy(true);
    setError(null);
    try {
      const c = await postJson<CheckView>("/ownership/checks", { evidenceId: repo.id });
      router.push(`/check/${c.id}`);
    } catch (err) {
      setError(message(err, "The check couldn\u2019t start. Try again."));
      setBusy(false);
    }
  }

  return (
    <li className={styles.defendRow}>
      <div className={styles.defendRepo}>
        {own?.defended ? <Seal score={own.score} size={52} /> : <span className={styles.defendEmpty} aria-hidden="true" />}
        <div>
          <p className={styles.defendName}>{repo.title}</p>
          <p className={`${common.muted} ${common.small}`}>
            {own?.defended
              ? `Defended with ${own.score} out of 100.`
              : own
                ? `Scored ${own.score} last time; 70 defends it.${waiting ? ` Try again after ${new Date(retakeAt).toLocaleString([], { weekday: "short", hour: "numeric", minute: "2-digit" })}.` : ""}`
                : `${repo.skills.map((s) => s.name).slice(0, 4).join(", ") || "No skills found yet"}`}
          </p>
        </div>
      </div>
      <div className={styles.defendActions}>
        {own ? (
          <Link href={own.defended ? `/t/${own.checkId}` : `/check/${own.checkId}`} className={styles.defendLink}>
            {own.defended ? "Transcript" : "Last attempt"}
          </Link>
        ) : null}
        {!own?.defended && !briefing ? (
          <button type="button" className="btn btn-primary" onClick={() => setBriefing(true)} disabled={waiting}>
            {own ? "Try again" : "Defend this repository"}
          </button>
        ) : null}
      </div>
      {briefing ? (
        <div className={styles.brief} role="region" aria-label={`Before you start the check on ${repo.title}`}>
          <ul>
            <li>
              <strong>12 minutes, 5 questions.</strong> Each one shows a part of {repo.title} and asks you to explain it.
            </li>
            <li>
              <strong>Your own words.</strong> Pasting and leaving the page are recorded. Notes are fine; copying an explanation isn&rsquo;t.
            </li>
            <li>
              <strong>70 defends it.</strong> If you pass, the transcript appears on your public profile. If not, try again in 24 hours.
            </li>
          </ul>
          {busy ? <PrepSteps /> : null}
          <div className={styles.briefActions}>
            <button type="button" className="btn btn-yellow" onClick={start} disabled={busy}>
              {busy ? "Reading your code\u2026" : "Start the clock"}
            </button>
            <button type="button" className="btn btn-quiet" onClick={() => setBriefing(false)} disabled={busy}>
              Not now
            </button>
          </div>
        </div>
      ) : null}
      {error ? (
        <p className={common.error} role="alert">
          {error}
        </p>
      ) : null}
    </li>
  );
}
