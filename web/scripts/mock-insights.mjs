// A compact port of the API's Insights (api/.../insights/core/Insights.java) for the mock API, so
// the interface can be built and demonstrated without Java. Same rules and copy; the Java version
// is the one that's tested.

const KINDS = {
  DATA: ["The Data Storyteller", "You turn messy data into something people can see and act on.", "data and databases"],
  MODEL: ["The Model Builder", "You teach machines to find patterns, then check that they really did.", "AI and machine learning"],
  PRODUCT: ["The Product Builder", "You build the things people click on: interfaces, APIs, the whole loop.", "web and product"],
  SYSTEMS: ["The Systems Engineer", "You care about what happens after deploy: pipelines, containers, uptime.", "infrastructure"],
  MOBILE: ["The Mobile Maker", "You build for the screen in someone's pocket.", "mobile"],
  SOLVER: ["The Problem Solver", "You go deep on the fundamentals: algorithms, structures, the hard part.", "computer science fundamentals"],
  POLYMATH: ["The Polymath", "You don't stay in one lane, and your work shows range most people don't have.", null],
  EXPLORER: ["The Explorer", "You're just getting started. Every repository you add sharpens this picture.", null],
};
const ORDER = Object.keys(KINDS);
const SHARES = {
  Data: { DATA: 1 }, Databases: { DATA: 0.6, SYSTEMS: 0.4 }, "AI & ML": { MODEL: 1 }, Web: { PRODUCT: 1 }, Design: { PRODUCT: 1 },
  "DevOps & Cloud": { SYSTEMS: 1 }, Mobile: { MOBILE: 1 }, "CS Fundamentals": { SOLVER: 1 },
};
const pct = (x) => `${Math.round(x * 100)}%`;
const listing = (a) => (a.length <= 1 ? a[0] ?? "" : `${a.slice(0, -1).join(", ")} and ${a[a.length - 1]}`);
const clock = (h) => `${h % 12 === 0 ? 12 : h % 12} ${h < 12 ? "am" : "pm"}`;
const DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
const isRepo = (w) => w.kind === "GITHUB_REPO" && !w.fork && !w.duplicate;
const isBuilt = (w) => isRepo(w) || w.kind === "PROJECT";
const argmax = (a) => a.reduce((b, v, i) => (v > a[b] ? i : b), 0);

function rhythmOf(work) {
  const weekdays = Array(7).fill(0), hours = Array(24).fill(0);
  let commits = 0;
  for (const w of work) {
    if (!isRepo(w) || !w.weekdays || !w.hours) continue;
    w.weekdays.forEach((v, i) => { weekdays[i] += v; commits += v; });
    w.hours.forEach((v, i) => { hours[i] += v; });
  }
  return commits ? { weekdays, hours, commits } : null;
}

function trait(work, rhythm) {
  const found = [];
  if (rhythm && rhythm.commits >= 20) {
    const n = rhythm.commits, h = rhythm.hours;
    const night = h[22] + h[23] + h[0] + h[1] + h[2] + h[3] + h[4], early = h[5] + h[6] + h[7] + h[8];
    const weekend = rhythm.weekdays[5] + rhythm.weekdays[6];
    if (night / n >= 0.35) found.push([night / n / 0.35, { key: "NIGHT_OWL", name: "Night Owl", line: `${pct(night / n)} of your commits land between 10 pm and 5 am.` }]);
    if (early / n >= 0.3) found.push([early / n / 0.3, { key: "EARLY_BIRD", name: "Early Bird", line: `${pct(early / n)} of your commits land before 9 am.` }]);
    if (weekend / n >= 0.4) found.push([weekend / n / 0.4, { key: "WEEKEND_BUILDER", name: "Weekend Builder", line: `${pct(weekend / n)} of your commits happen on Saturdays and Sundays.` }]);
  }
  const repos = work.filter(isRepo);
  const weeks = repos.reduce((n, w) => n + w.activeWeeks, 0), commits = repos.reduce((n, w) => n + w.ownCommits, 0);
  const firsts = repos.map((w) => Date.parse(w.firstActive ?? w.lastActive)), lasts = repos.map((w) => Date.parse(w.lastActive));
  if (repos.length && weeks >= 8) {
    const span = Math.max(1, (Math.max(...lasts) - Math.min(...firsts)) / (7 * 86_400_000) + 1);
    const share = Math.min(1, weeks / span);
    if (share >= 0.5) found.push([share / 0.5, { key: "STEADY", name: "Steady", line: `You committed in ${weeks} of the ${Math.round(span)} weeks since your first repository here.` }]);
  }
  if (weeks > 0 && commits >= 20 && commits / weeks >= 8) {
    found.push([commits / weeks / 8, { key: "SPRINTER", name: "Sprinter", line: `When you work, you go all in: ${Math.round(commits / weeks)} commits in an active week, on average.` }]);
  }
  const finished = repos.filter((w) => w.activeWeeks >= 4).length;
  if (finished >= 3) found.push([finished / 3, { key: "FINISHER", name: "Finisher", line: `${finished} of your repositories were worked on for a month or more, not abandoned after a weekend.` }]);
  found.sort((a, b) => b[0] - a[0]);
  return found[0]?.[1] ?? null;
}

function archetype(skills, work, rhythm) {
  const backed = skills.filter((s) => s.level !== "CLAIMED" && s.score > 0);
  const weight = {}, members = {};
  let total = 0;
  for (const s of backed) for (const [k, f] of Object.entries(SHARES[s.domain] ?? {})) {
    weight[k] = (weight[k] ?? 0) + s.score * f;
    (members[k] ??= []).push(s);
    total += s.score * f;
  }
  const t = trait(work, rhythm);
  const all = backed.reduce((n, s) => n + s.score, 0);
  if (backed.length < 3 || all < 90 || total === 0) {
    return { key: "EXPLORER", name: KINDS.EXPLORER[0], line: KINDS.EXPLORER[1],
      reason: backed.length === 0 ? "No skills are backed by work yet." : `${backed.length} ${backed.length === 1 ? "skill is" : "skills are"} backed by work so far.`,
      skills: backed.slice(0, 3).map((s) => s.name), trait: t };
  }
  const ranked = Object.entries(weight).sort((a, b) => b[1] - a[1] || ORDER.indexOf(a[0]) - ORDER.indexOf(b[0]));
  const [top, topW] = ranked[0];
  const share = topW / total;
  const areas = ranked.filter(([, w]) => w / total >= 0.15).length;
  if (share < 0.38 && areas >= 3) {
    const named = ranked.slice(0, 3).map(([k]) => KINDS[k][2]);
    return { key: "POLYMATH", name: KINDS.POLYMATH[0], line: KINDS.POLYMATH[1],
      reason: `Your proven skill is spread across ${named[0]}, ${named[1]} and ${named[2]}, with no area above ${pct(share)}.`,
      skills: ranked.slice(0, 3).map(([k]) => [...members[k]].sort((a, b) => b.score - a.score)[0].name), trait: t };
  }
  const picks = [...new Set([...members[top]].sort((a, b) => b.score - a.score).map((s) => s.name))].slice(0, 3);
  return { key: top, name: KINDS[top][0], line: KINDS[top][1], reason: `${pct(share)} of your proven skill is in ${KINDS[top][2]}: ${listing(picks)}.`, skills: picks, trait: t };
}

function constellation(skills, work) {
  const present = new Set(skills.map((s) => s.id));
  const pairs = new Map();
  for (const w of work) {
    if (!isBuilt(w)) continue;
    const ids = w.skills.map((s) => s.id).filter((id) => present.has(id)).sort((a, b) => a - b);
    for (let i = 0; i < ids.length; i++) for (let j = i + 1; j < ids.length; j++) {
      const k = `${ids[i]}:${ids[j]}`;
      pairs.set(k, (pairs.get(k) ?? 0) + 1);
    }
  }
  const links = [...pairs.entries()].map(([k, weight]) => { const [a, b] = k.split(":").map(Number); return { a, b, weight }; })
    .sort((x, y) => y.weight - x.weight || x.a - y.a || x.b - y.b).slice(0, 80);
  return { stars: skills.map(({ id, name, domain, score, level, topPercent }) => ({ id, name, domain, score, level, topPercent })), links };
}

const modeRank = (w) => ({ USED: 3, LEARNING: 2, ASKED: 1 })[w.chatMode] ?? 0;

function saidText(w) {
  let base;
  if (w.chatSource === "MEMORY") base = { USED: "Your AI remembers you using it.", LEARNING: "Your AI remembers you learning it." }[w.chatMode] ?? "Your AI remembers you asking about it.";
  else base = w.chatConversations > 0 ? `Came up in ${w.chatConversations} of your AI conversations.` : "Came up in your AI conversations.";
  if (!w.chatNote) return base;
  const n = w.chatNote.trim();
  return `${base} ${n[0].toUpperCase()}${n.slice(1)}${/[.!?]$/.test(n) ? "" : "."}`;
}

function doneText(pieces, repos, commits) {
  if (repos === 0) return pieces === 1 ? "Used in 1 project." : `Used in ${pieces} projects.`;
  const r = repos === 1 ? "1 repository" : `${repos} repositories`, c = commits === 1 ? "1 commit" : `${commits} commits`;
  const projects = pieces - repos;
  return projects > 0 ? `${r} (${c}) and ${projects === 1 ? "1 project." : `${projects} projects.`}` : `${r}, ${c}.`;
}

function saidDone(skills, work, names) {
  const said = new Map();
  for (const w of work) {
    if (w.kind !== "CHAT_EXPORT") continue;
    for (const s of [...w.skills, ...(w.pendingSkills ?? [])]) {
      const prev = said.get(s.id);
      if (!prev || modeRank(w) > modeRank(prev) || w.chatConversations > prev.chatConversations) said.set(s.id, w);
    }
  }
  const done = new Map();
  for (const w of work) {
    if (!isBuilt(w)) continue;
    for (const s of w.skills) {
      const d = done.get(s.id) ?? { pieces: 0, commits: 0, repos: 0 };
      done.set(s.id, { pieces: d.pieces + 1, commits: d.commits + w.ownCommits, repos: d.repos + (isRepo(w) ? 1 : 0) });
    }
  }
  const name = (id) => names.get(id) ?? `Skill ${id}`;
  const edges = [], unproven = [], strengths = [], inSync = [];
  [...said.keys()].sort((a, b) => said.get(b).chatConversations - said.get(a).chatConversations || modeRank(said.get(b)) - modeRank(said.get(a)))
    .forEach((id) => {
      const w = said.get(id), d = done.get(id);
      if (!d) (w.chatMode === "USED" ? unproven : edges).push({ skillId: id, name: name(id), said: saidText(w), done: "Nothing public shows it yet." });
      else inSync.push({ skillId: id, name: name(id), said: saidText(w), done: doneText(d.pieces, d.repos, d.commits) });
    });
  [...done.entries()].filter(([id, d]) => !said.has(id) && (d.pieces >= 2 || d.commits >= 20))
    .sort((a, b) => b[1].commits - a[1].commits || b[1].pieces - a[1].pieces || a[0] - b[0])
    .forEach(([id, d]) => strengths.push({ skillId: id, name: name(id), said: "Never came up in your AI history.", done: doneText(d.pieces, d.repos, d.commits) }));
  if (said.size === 0 && strengths.length === 0) return null;
  const cap = (l) => l.slice(0, 6);
  return { edges: cap(edges), unproven: cap(unproven), strengths: cap(strengths), inSync: cap(inSync), hasHistory: said.size > 0 };
}

function insights(skills, work, rhythm) {
  const out = [];
  const repos = work.filter(isRepo);
  const now = Date.now(), year = 365 * 86_400_000;
  const top = skills.filter((s) => s.topPercent != null).sort((a, b) => a.topPercent - b.topPercent || b.score - a.score)[0];
  if (top) out.push([10 - Math.log(top.topPercent), { key: "TOP_SKILL", title: `Top ${top.topPercent}% in ${top.name}`,
    detail: `Your ${top.name} score of ${Math.round(top.score)} beats ${100 - top.topPercent}% of candidates on Notch with the same skill.` }]);
  const deep = repos.filter((w) => w.activeWeeks >= 3).sort((a, b) => b.activeWeeks - a.activeWeeks || b.ownCommits - a.ownCommits)[0];
  if (deep) out.push([6 + Math.min(3, deep.activeWeeks / 6), { key: "DEEPEST", title: `Your deepest work is ${deep.title}`,
    detail: `${deep.ownCommits} commits across ${deep.activeWeeks} different weeks. Depth like this is what recruiters look for and rarely find.` }]);
  const defended = repos.filter((w) => w.ownership?.defended).length;
  if (defended) out.push([9, { key: "DEFENDED", title: defended === 1 ? "You defended a repository" : `You defended ${defended} repositories`,
    detail: "You explained your own code against the clock and passed. Few candidates have this, and a defended repository can take a skill past 85 on its own." }]);
  const started = (w) => (w.firstActive ? Date.parse(w.firstActive) : null);
  const thisYear = repos.filter((w) => started(w) && started(w) > now - year).length;
  const lastYear = repos.filter((w) => started(w) && started(w) <= now - year && started(w) > now - 2 * year).length;
  if (thisYear >= 2 && thisYear > lastYear) out.push([7 + Math.min(2, thisYear - lastYear) / 2, { key: "GROWTH", title: "You're building more",
    detail: `You started ${thisYear} repositories in the last 12 months${lastYear === 0 ? ", and none in the year before." : `, up from ${lastYear} in the year before.`}` }]);
  if (rhythm && rhythm.commits >= 20) {
    const d = argmax(rhythm.weekdays), ds = rhythm.weekdays[d] / rhythm.commits;
    if (ds >= 0.25) out.push([5 + ds * 4, { key: "DAY", title: `${DAYS[d]} is your day`, detail: `${pct(ds)} of your commits land on a ${DAYS[d]}, more than any other day.` }]);
    const h = argmax(rhythm.hours), hs = rhythm.hours[h] / rhythm.commits;
    if (hs >= 0.12) out.push([4 + hs * 6, { key: "HOUR", title: `Your hour is ${clock(h)}`, detail: `More of your commits happen between ${clock(h)} and ${clock((h + 1) % 24)} than in any other hour.` }]);
  }
  if (repos.length) {
    const days = Math.floor((now - Math.max(...repos.map((w) => Date.parse(w.lastActive)))) / 86_400_000);
    if (days >= 75) out.push([8, { key: "QUIET", title: `It's been ${Math.floor(days / 30)} months since your last commit`,
      detail: "Scores fade slowly as work gets older. One small push to any repository brings them back up." }]);
  }
  const verified = skills.filter((s) => s.level === "VERIFIED" || s.level === "DEFENDED").length;
  if (verified >= 3) out.push([3 + Math.min(3, verified / 3), { key: "VERIFIED", title: `${verified} skills verified by your own commits`,
    detail: "Each one is backed by a repository you worked on across at least two weeks, not just a line on a resume." }]);
  return out.sort((a, b) => b[0] - a[0]).slice(0, 4).map(([, i]) => i);
}

/** The read for a profile view (skills) and its stored evidence. */
export function readOf(view, evidence, names, owner) {
  const skills = [...view.skills].sort((a, b) => b.score - a.score);
  const rhythm = rhythmOf(evidence);
  const repos = evidence.filter(isRepo);
  const built = evidence.filter(isBuilt).map((w) => Date.parse(w.firstActive ?? w.lastActive));
  const stats = { repositories: repos.length, commits: repos.reduce((n, w) => n + w.ownCommits, 0),
    activeWeeks: repos.reduce((n, w) => n + w.activeWeeks, 0), skills: skills.filter((s) => s.level !== "CLAIMED" && s.score > 0).length,
    defended: repos.filter((w) => w.ownership?.defended).length, since: built.length ? new Date(Math.min(...built)).toISOString() : null };
  return { archetype: archetype(skills, evidence, rhythm), constellation: constellation(skills, evidence),
    saidDone: owner ? saidDone(skills, evidence, names) : null, insights: owner ? insights(skills, evidence, rhythm) : [], stats, rhythm };
}

export const MEMORY_PROMPT = `I'm building my profile on Notch, a portfolio for students. Using only what you remember from our past conversations, fill in the template below. Don't guess or invent anything: if you don't know, leave that section empty. Keep my wording where you can.

For each skill, choose one word: USED (I built something with it), LEARNING (I'm studying it), or ASKED (I asked you about it). Add one short line saying what you remember.

Reply with the template only, exactly in this format:

NOTCH PROFILE
SKILLS
- skill name | USED or LEARNING or ASKED | what you remember, in one line
PROJECTS
- project name | what it does, in one line | skills used, separated by commas
GOAL
- the role or job I'm aiming for, if I've told you
END`;

/** A small parser for the memory reply, matching MemoryImport.parse's main behaviour. */
export function parseMemory(text, byName) {
  if (!text || !text.trim()) throw new Error("Paste the reply from your AI assistant.");
  let section = null, sawHeader = false, sawSection = false, goal = null;
  const skills = new Map(), projects = [], unrecognised = [];
  for (const raw of text.replace(/\r/g, "").split("\n")) {
    const line = raw.replace(/\*\*|__|`+|^\s*(?:#{1,6}|>)\s*/g, "").trim();
    if (!line) continue;
    const upper = line.toUpperCase().replace(/[:.]+$/, "").trim();
    if (upper === "NOTCH PROFILE") { sawHeader = true; continue; }
    if (upper === "END") break;
    if (["SKILLS", "PROJECTS", "GOAL"].includes(upper)) { section = upper; sawSection = true; continue; }
    const body = line.replace(/^\s*(?:[-*•–]|\d+[.)])\s+/, "").trim();
    if (!body || /^(none|n\/?a|nothing|-|unknown|empty)\.?$/i.test(body)) continue;
    const parts = body.split(/\s*\|\s*/);
    if (section === "SKILLS") {
      const s = byName.get(parts[0].toLowerCase());
      if (!s) { unrecognised.push(parts[0]); continue; }
      const w = (parts[1] ?? "").toUpperCase();
      const mode = w.startsWith("USE") ? "USED" : w.startsWith("LEARN") ? "LEARNING" : "ASKED";
      const prev = skills.get(s.id);
      const rank = { USED: 3, LEARNING: 2, ASKED: 1 };
      if (!prev || rank[mode] > rank[prev.mode]) skills.set(s.id, { skill: s, mode, note: parts.slice(2).join(" | ") || null });
    } else if (section === "PROJECTS" && projects.length < 10) {
      const used = (parts[2] ?? "").split(/[,;/]/).map((n) => byName.get(n.trim().toLowerCase())).filter(Boolean);
      projects.push({ title: parts[0], description: parts[1] ?? null, skills: used });
    } else if (section === "GOAL" && !goal) goal = body.slice(0, 80);
  }
  if (!sawHeader && !sawSection) throw new Error("This doesn't look like a reply to Notch's prompt. Copy the prompt again, paste it into ChatGPT or Claude, and paste back the whole reply.");
  return { skills: [...skills.values()], projects, unrecognised, goal };
}
