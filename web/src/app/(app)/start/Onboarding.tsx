"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { LevelChip } from "@/components/portfolio/SkillBars";
import { SkillPicker } from "@/components/portfolio/SkillPicker";
import { call, ClientApiError, postJson } from "@/lib/client-api";
import type { ChatImportResult, GitHubSyncResult, ProfileView, SkillRef } from "@/lib/portfolio-types";
import type { User } from "@/lib/types";
import common from "@/components/portfolio/portfolio.module.css";
import styles from "./onboarding.module.css";

function message(err: unknown, fallback: string): string {
  return err instanceof ClientApiError ? err.message : fallback;
}

/** The candidate's home: connect GitHub, add chats and projects, approve skills, publish. */
export function Onboarding({ user, profile }: { user: User; profile: ProfileView }) {
  const router = useRouter();
  const pending = profile.evidence.filter((e) => e.pendingSkills.length > 0);
  const hasRepos = profile.evidence.some((e) => e.kind === "GITHUB_REPO");
  const step = !hasRepos ? 1 : pending.length > 0 ? 3 : profile.hasProfile && profile.isPublic ? 4 : 3;

  return (
    <div className={styles.flow}>
      <ol className={styles.progress} aria-label="Progress">
        {["Connect GitHub", "Add chats and projects", "Approve skills", "Publish"].map((label, i) => (
          <li key={label} aria-current={step === i + 1 ? "step" : undefined} className={step > i + 1 ? styles.done : undefined}>
            <span className={styles.bar} />
            <span>
              {i + 1} · {label}
            </span>
          </li>
        ))}
      </ol>

      {profile.hasProfile && profile.isPublic && profile.handle ? (
        <p className={common.notice}>
          Your profile is live at <Link href={`/u/${profile.handle}`}>/u/{profile.handle}</Link>.
        </p>
      ) : null}

      <GitHubSection user={user} hasRepos={hasRepos} onDone={() => router.refresh()} />
      <ChatSection onDone={() => router.refresh()} />
      <ProjectSection onDone={() => router.refresh()} />
      <ApproveSection profile={profile} onDone={() => router.refresh()} />
      <PublishSection profile={profile} onDone={() => router.refresh()} />
    </div>
  );
}

function GitHubSection({ user, hasRepos, onDone }: { user: User; hasRepos: boolean; onDone: () => void }) {
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
    <section className={common.card} aria-labelledby="gh">
      <h2 id="gh" className={common.cardTitle}>
        GitHub
      </h2>
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
    </section>
  );
}

function ChatSection({ onDone }: { onDone: () => void }) {
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
    <section className={common.card} aria-labelledby="chats">
      <h2 id="chats" className={common.cardTitle}>
        AI chat history <span className={common.muted}>· optional</span>
      </h2>
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
    </section>
  );
}

function ProjectSection({ onDone }: { onDone: () => void }) {
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
    <section className={common.card} aria-labelledby="proj">
      <h2 id="proj" className={common.cardTitle}>
        Add what GitHub can&rsquo;t see
      </h2>
      <p className={`${common.muted} ${common.small}`}>A hackathon, a club you run, a weekend build. Projects count as shown evidence.</p>
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
    </section>
  );
}

function ApproveSection({ profile, onDone }: { profile: ProfileView; onDone: () => void }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fromWork = profile.skills.filter((s) => s.level !== "CLAIMED");
  const pending = profile.evidence.filter((e) => e.kind === "CHAT_EXPORT" && e.pendingSkills.length > 0);

  async function decide(evidenceId: string, skillId: number, approve: boolean) {
    setBusy(evidenceId);
    setError(null);
    try {
      if (approve) await postJson(`/portfolio/evidence/${evidenceId}/skills/${skillId}`, { approved: true }, "PATCH");
      else await call(`/portfolio/evidence/${evidenceId}`, { method: "DELETE" });
      onDone();
    } catch (err) {
      setError(message(err, "That didn't save. Try again."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className={`${common.card} ${styles.focus}`} aria-labelledby="approve">
      <h2 id="approve" className={common.cardTitle}>
        Your skills
      </h2>
      <h3 className={`${common.eyebrow} ${styles.sub}`}>From your work</h3>
      {fromWork.length > 0 ? (
        <ul className={styles.skillChips}>
          {fromWork.map((s) => (
            <li key={s.id}>
              <span className={styles.skillChip}>
                {s.name} <LevelChip level={s.level} />
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className={`${common.muted} ${common.small}`}>Connect GitHub or add a project, and skills appear here.</p>
      )}

      {pending.length > 0 ? (
        <>
          <h3 className={`${common.eyebrow} ${styles.sub}`}>Suggested from your chats</h3>
          <ul className={styles.suggestions}>
            {pending.map((e) =>
              e.pendingSkills.map((s) => (
                <li key={`${e.id}-${s.id}`} className={styles.suggestion}>
                  <div className={styles.suggestionText}>
                    <strong>{s.name}</strong>
                    <span className={`${common.muted} ${common.small}`}>{e.title}</span>
                  </div>
                  <button type="button" className="btn btn-quiet" disabled={busy === e.id} onClick={() => decide(e.id, s.id, false)}>
                    Not me
                  </button>
                  <button type="button" className="btn btn-primary" disabled={busy === e.id} onClick={() => decide(e.id, s.id, true)}>
                    Add to profile
                  </button>
                </li>
              )),
            )}
          </ul>
        </>
      ) : null}
      {error ? (
        <p className={common.error} role="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}

function PublishSection({ profile, onDone }: { profile: ProfileView; onDone: () => void }) {
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
