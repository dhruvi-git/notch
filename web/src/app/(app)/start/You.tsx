"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { CountUp } from "@/components/CountUp";
import { Constellation, ConstellationKey } from "@/components/read/Constellation";
import { postJson } from "@/lib/client-api";
import type { ProfileView } from "@/lib/portfolio-types";
import type { MemoryImportResult, MyRead, Pair, Read, SaidDone } from "@/lib/read-types";
import type { User } from "@/lib/types";
import common from "@/components/portfolio/portfolio.module.css";
import { ChatSection, DefendSection, GitHubSection, message, ProjectSection, PublishSection, ReviewSection } from "./Onboarding";
import styles from "./you.module.css";

type Tab = "github" | "memory" | "export" | "project";

/** The candidate's home: their read first, then the ways to add to it. */
export function You({ user, profile, mine, prompt }: { user: User; profile: ProfileView; mine: MyRead; prompt: string }) {
  const router = useRouter();
  const refresh = () => router.refresh();
  const read = mine.read;
  const empty = read.constellation.stars.length === 0;
  const hasRepos = profile.evidence.some((e) => e.kind === "GITHUB_REPO");
  const [tab, setTab] = useState<Tab>(hasRepos ? "memory" : "github");

  function openSource(t: Tab) {
    setTab(t);
    requestAnimationFrame(() => document.getElementById("add")?.scrollIntoView({ behavior: "smooth", block: "start" }));
  }

  return (
    <>
      <Hero read={read} goal={mine.goal} profile={profile} empty={empty} onAdd={() => openSource(hasRepos ? "memory" : "github")} />
      <main className={styles.main}>
        {!empty && read.insights.length > 0 ? <InsightList read={read} /> : null}
        {!empty ? <SaidVsDone saidDone={read.saidDone} onImport={() => openSource("memory")} /> : null}
        <div id="review-anchor">
          <ReviewSection profile={profile} onDone={refresh} />
        </div>
        <Sources user={user} tab={tab} setTab={setTab} hasRepos={hasRepos} prompt={prompt} profile={profile} onDone={refresh} />
        {hasRepos ? <DefendSection profile={profile} /> : null}
        <div id="publish">
          <PublishSection profile={profile} onDone={refresh} />
        </div>
      </main>
    </>
  );
}

// ---------------------------------------------------------------------------------------------- hero

function signature(read: Read) {
  return read.constellation.stars.map((s) => `${s.id}${s.level[0]}`).join(".");
}

/** Plays the reveal the first time a constellation is seen, and again whenever it changes. */
function useReveal(sig: string) {
  const [reveal, setReveal] = useState(false);
  useEffect(() => {
    if (!sig) return;
    try {
      if (window.localStorage.getItem("notch:constellation") !== sig) {
        setReveal(true);
        window.localStorage.setItem("notch:constellation", sig);
      }
    } catch {
      // Storage can be unavailable (private windows); the page works without the reveal.
    }
  }, [sig]);
  return reveal;
}

function since(iso: string | null) {
  if (!iso) return null;
  return new Date(iso).toLocaleDateString("en-IN", { month: "short", year: "numeric" });
}

function Hero({ read, goal, profile, empty, onAdd }: { read: Read; goal: string | null; profile: ProfileView; empty: boolean; onAdd: () => void }) {
  const sig = useMemo(() => signature(read), [read]);
  const reveal = useReveal(sig);
  const a = read.archetype;
  const s = read.stats;
  const published = profile.hasProfile && profile.isPublic && profile.handle;

  if (empty) {
    return (
      <section className={`${styles.hero} ${styles.heroEmpty} on-brand`} aria-labelledby="hero-title">
        <div className={styles.heroInner}>
          <div className={styles.heroText}>
            <h1 id="hero-title" className={styles.emptyTitle}>
              Let&rsquo;s read your work.
            </h1>
            <p className={styles.lede}>
              Connect GitHub, or paste what your AI assistant remembers about you. In under a minute Notch draws your skills as a
              constellation, tells you what kind of builder you are, and shows what to do next.
            </p>
            <div className={styles.actions}>
              <button type="button" className="btn btn-yellow" onClick={onAdd}>
                Connect GitHub
              </button>
            </div>
          </div>
          <div className={styles.emptySky} aria-hidden="true">
            <Constellation stars={PREVIEW.stars} links={PREVIEW.links} labels={0} showAreas={false} />
          </div>
        </div>
      </section>
    );
  }

  const lead =
    s.repositories > 0
      ? `From ${s.repositories} ${s.repositories === 1 ? "repository" : "repositories"} and ${s.commits.toLocaleString("en-IN")} commits, you’re`
      : `From ${s.skills} ${s.skills === 1 ? "skill" : "skills"} backed by your work, you’re`;

  return (
    <section className={`${styles.hero} on-brand`} aria-labelledby="hero-title">
      <div className={styles.heroInner}>
        <div className={styles.heroText}>
          <p className={styles.lead}>{lead}</p>
          <h1 id="hero-title" className={`${styles.archetype} ${reveal ? styles.arrive : ""}`}>
            {a.name}
          </h1>
          <p className={styles.line}>{a.line}</p>
          {a.trait ? (
            <p className={styles.trait}>
              <span className={styles.traitName}>{a.trait.name}</span>
              <span>{a.trait.line}</span>
            </p>
          ) : null}
          <p className={styles.reason}>{a.reason}</p>
          {goal ? (
            <p className={styles.goal}>
              Aiming for <strong>{goal}</strong>
            </p>
          ) : null}
          <dl className={styles.stats}>
            {s.repositories > 0 ? (
              <div>
                <dt>repositories</dt>
                <dd>
                  <CountUp value={s.repositories} />
                </dd>
              </div>
            ) : null}
            <div>
              <dt>skills with proof</dt>
              <dd>
                <CountUp value={s.skills} delay={120} />
              </dd>
            </div>
            {s.activeWeeks > 0 ? (
              <div>
                <dt>weeks you shipped</dt>
                <dd>
                  <CountUp value={s.activeWeeks} delay={240} />
                </dd>
              </div>
            ) : null}
            {s.since ? (
              <div>
                <dt>building since</dt>
                <dd>{since(s.since)}</dd>
              </div>
            ) : null}
          </dl>
          <div className={styles.actions}>
            {published ? (
              <Link href={`/u/${profile.handle}`} className="btn btn-yellow">
                Open my profile
              </Link>
            ) : (
              <a href="#publish" className="btn btn-yellow">
                Publish my profile
              </a>
            )}
            <button type="button" className="btn btn-quiet" onClick={onAdd}>
              Add more work
            </button>
          </div>
        </div>
        <div className={styles.sky}>
          <Constellation stars={read.constellation.stars} links={read.constellation.links} reveal={reveal} />
          <ConstellationKey className={styles.key} />
        </div>
      </div>
    </section>
  );
}

/** A placeholder sky for the empty state: a sketch of what's coming, not anyone's data. */
const PREVIEW = {
  stars: [
    { id: 1, name: "", domain: "a", score: 80, level: "VERIFIED" },
    { id: 2, name: "", domain: "a", score: 55, level: "SHOWN" },
    { id: 3, name: "", domain: "a", score: 40, level: "SHOWN" },
    { id: 4, name: "", domain: "b", score: 90, level: "DEFENDED" },
    { id: 5, name: "", domain: "b", score: 60, level: "VERIFIED" },
    { id: 6, name: "", domain: "c", score: 30, level: "CLAIMED" },
    { id: 7, name: "", domain: "c", score: 65, level: "VERIFIED" },
    { id: 8, name: "", domain: "c", score: 45, level: "SHOWN" },
  ],
  links: [
    { a: 1, b: 2, weight: 2 },
    { a: 1, b: 4, weight: 1 },
    { a: 4, b: 5, weight: 2 },
    { a: 5, b: 7, weight: 1 },
    { a: 7, b: 8, weight: 1 },
    { a: 2, b: 3, weight: 1 },
  ],
};

// ---------------------------------------------------------------------------------------------- insights

function InsightList({ read }: { read: Read }) {
  return (
    <section className={styles.block} aria-labelledby="insights">
      <h2 id="insights" className={styles.blockTitle}>
        What your work says
      </h2>
      <ul className={styles.insights}>
        {read.insights.map((i) => (
          <li key={i.key}>
            <p className={styles.insightTitle}>{i.title}</p>
            <p className={styles.insightDetail}>{i.detail}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

// ---------------------------------------------------------------------------------------------- said vs done

function SaidVsDone({ saidDone, onImport }: { saidDone: SaidDone | null; onImport: () => void }) {
  const said = saidDone ? [...saidDone.edges, ...saidDone.unproven] : [];
  const both = saidDone?.inSync ?? [];
  const done = saidDone?.strengths ?? [];
  const first = both[0] ?? said[0] ?? done[0] ?? null;
  const [picked, setPicked] = useState<Pair | null>(first);
  const hasHistory = saidDone?.hasHistory ?? false;
  const edge = saidDone?.edges[0];
  const unproven = saidDone?.unproven ?? [];

  const chip = (p: Pair, kind: "said" | "both" | "done") => (
    <li key={`${kind}-${p.skillId}`}>
      <button
        type="button"
        className={`${styles.vChip} ${styles[`v_${kind}`]} ${picked?.skillId === p.skillId ? styles.vChipOn : ""}`}
        aria-pressed={picked?.skillId === p.skillId}
        onClick={() => setPicked(p)}
      >
        {p.name}
      </button>
    </li>
  );

  return (
    <section className={styles.block} aria-labelledby="said-done">
      <div className={styles.blockHead}>
        <h2 id="said-done" className={styles.blockTitle}>
          Said vs done
        </h2>
        <p className={styles.blockLede}>What you talk to AI about, against what your public work shows. Only you see this.</p>
      </div>

      <div className={styles.venn}>
        <svg className={styles.vennRings} viewBox="0 0 1000 420" preserveAspectRatio="none" aria-hidden="true">
          <ellipse cx="370" cy="210" rx="340" ry="200" className={styles.ringSaid} />
          <ellipse cx="630" cy="210" rx="340" ry="200" className={styles.ringDone} />
        </svg>
        <div className={styles.vennCol}>
          <h3 className={styles.vennLabel}>You talk about it</h3>
          {hasHistory ? (
            said.length > 0 ? (
              <ul className={styles.vChips}>{said.map((p) => chip(p, "said"))}</ul>
            ) : (
              <p className={styles.vennNone}>Everything you talk about, you&rsquo;ve built with.</p>
            )
          ) : (
            <button type="button" className={styles.vennInvite} onClick={onImport}>
              Bring in your AI history to fill this side
            </button>
          )}
        </div>
        <div className={`${styles.vennCol} ${styles.vennMid}`}>
          <h3 className={styles.vennLabel}>Both</h3>
          {both.length > 0 ? <ul className={styles.vChips}>{both.map((p) => chip(p, "both"))}</ul> : <p className={styles.vennNone}>Nothing yet</p>}
        </div>
        <div className={`${styles.vennCol} ${styles.vennRight}`}>
          <h3 className={styles.vennLabel}>You build with it</h3>
          {done.length > 0 ? <ul className={styles.vChips}>{done.map((p) => chip(p, "done"))}</ul> : <p className={styles.vennNone}>Nothing yet</p>}
        </div>
      </div>

      {picked ? (
        <p className={styles.vennDetail} aria-live="polite">
          <strong>{picked.name}.</strong> {picked.said} {picked.done}
        </p>
      ) : null}

      {edge || unproven.length > 0 ? (
        <div className={styles.callouts}>
          {edge ? (
            <p>
              <strong>Your biggest learning edge is {edge.name}.</strong> {edge.said} Nothing public uses it yet, so one small project would
              move it from talk to proof.
            </p>
          ) : null}
          {unproven.length > 0 ? (
            <p>
              <strong>
                Your AI remembers you using {listing(unproven.map((u) => u.name))}, but nothing public shows{" "}
                {unproven.length === 1 ? "it" : "them"}.
              </strong>{" "}
              Push the code, or add the project, and {unproven.length === 1 ? "it counts" : "they count"}.
            </p>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

function listing(a: string[]) {
  return a.length <= 1 ? (a[0] ?? "") : `${a.slice(0, -1).join(", ")} and ${a[a.length - 1]}`;
}

// ---------------------------------------------------------------------------------------------- sources

const TABS: { id: Tab; label: string }[] = [
  { id: "github", label: "GitHub" },
  { id: "memory", label: "AI memory" },
  { id: "export", label: "Chat export" },
  { id: "project", label: "A project" },
];

function Sources({
  user,
  tab,
  setTab,
  hasRepos,
  prompt,
  profile,
  onDone,
}: {
  user: User;
  tab: Tab;
  setTab: (t: Tab) => void;
  hasRepos: boolean;
  prompt: string;
  profile: ProfileView;
  onDone: () => void;
}) {
  const repos = profile.evidence.filter((e) => e.kind === "GITHUB_REPO").length;
  const memory = profile.evidence.some((e) => e.kind === "CHAT_EXPORT" && e.title.endsWith("from AI memory"));
  const exported = profile.evidence.some((e) => e.kind === "CHAT_EXPORT" && !e.title.endsWith("from AI memory"));
  const projects = profile.evidence.filter((e) => e.kind === "PROJECT").length;
  const status: Record<Tab, string> = {
    github: repos ? `${repos} ${repos === 1 ? "repository" : "repositories"}` : "Not connected",
    memory: memory ? "Imported" : "Fastest way in",
    export: exported ? "Imported" : "Optional",
    project: projects ? `${projects} added` : "Hackathons, clubs, builds",
  };
  return (
    <section id="add" className={styles.sources} aria-labelledby="add-title">
      <h2 id="add-title" className={styles.blockTitle}>
        Add to your read
      </h2>
      <div className={styles.tabs} role="tablist" aria-label="Sources">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            id={`tab-${t.id}`}
            aria-selected={tab === t.id}
            aria-controls={`panel-${t.id}`}
            className={styles.tab}
            onClick={() => setTab(t.id)}
          >
            <span className={styles.tabName}>{t.label}</span>
            <span className={styles.tabStatus}>{status[t.id]}</span>
          </button>
        ))}
      </div>
      <div key={tab} className={`${styles.panel} rise`} role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === "github" ? <GitHubSection user={user} hasRepos={hasRepos} onDone={onDone} /> : null}
        {tab === "memory" ? <MemoryPane prompt={prompt} onDone={onDone} /> : null}
        {tab === "export" ? <ChatSection onDone={onDone} /> : null}
        {tab === "project" ? <ProjectSection onDone={onDone} /> : null}
      </div>
    </section>
  );
}

/** Paste a prompt into ChatGPT or Claude, paste the reply back. No export file needed. */
function MemoryPane({ prompt, onDone }: { prompt: string; onDone: () => void }) {
  const [copied, setCopied] = useState(false);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<MemoryImportResult | null>(null);

  async function copy() {
    try {
      await navigator.clipboard.writeText(prompt);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2500);
    } catch {
      setError("Your browser blocked copying. Select the prompt and copy it yourself.");
    }
  }

  async function read(e: React.FormEvent) {
    e.preventDefault();
    if (!text.trim()) {
      setError("Paste the reply from ChatGPT or Claude first.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const r = await postJson<MemoryImportResult>("/portfolio/memory-import", { text });
      setResult(r);
      setText("");
      onDone();
    } catch (err) {
      setError(message(err, "The reply couldn't be read. Try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.memory}>
      <p className={`${common.muted} ${common.small} ${styles.memoryIntro}`}>
        ChatGPT and Claude remember what you&rsquo;ve built and what you&rsquo;re learning. Ask yours, and paste the answer here. Nothing
        counts until you approve it.
      </p>
      <ol className={styles.memorySteps}>
        <li>
          <div className={styles.stepHead}>
            <span className={styles.stepNum}>1</span>
            <h3>Copy this prompt</h3>
          </div>
          <pre className={styles.prompt} tabIndex={0}>
            {prompt}
          </pre>
          <div className={styles.stepActions}>
            <button type="button" className="btn btn-primary" onClick={copy}>
              {copied ? "Copied" : "Copy prompt"}
            </button>
            <a className="btn btn-quiet" href="https://chatgpt.com/" target="_blank" rel="noopener noreferrer">
              Open ChatGPT
            </a>
            <a className="btn btn-quiet" href="https://claude.ai/new" target="_blank" rel="noopener noreferrer">
              Open Claude
            </a>
          </div>
        </li>
        <li>
          <form onSubmit={read}>
            <div className={styles.stepHead}>
              <span className={styles.stepNum}>2</span>
              <h3>
                <label htmlFor="memory-reply">Paste the reply</label>
              </h3>
            </div>
            <textarea
              id="memory-reply"
              className={styles.reply}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={"NOTCH PROFILE\nSKILLS\n- …"}
              maxLength={20000}
              spellCheck={false}
            />
            <div className={styles.stepActions}>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                {busy ? "Reading…" : "Read the reply"}
              </button>
            </div>
          </form>
        </li>
      </ol>
      {error ? (
        <p className={common.error} role="alert">
          {error}
        </p>
      ) : null}
      {result ? (
        <div className={common.notice} role="status">
          Found {result.skills.length} {result.skills.length === 1 ? "skill" : "skills"}
          {result.projects.length ? ` and ${result.projects.length} ${result.projects.length === 1 ? "project" : "projects"}` : ""}
          {result.goal ? `, and that you're aiming for ${result.goal}` : ""}. Review them just above.
          {result.unrecognised.length > 0 ? ` Not in Notch's catalogue yet: ${result.unrecognised.join(", ")}.` : ""}
        </div>
      ) : null}
    </div>
  );
}
