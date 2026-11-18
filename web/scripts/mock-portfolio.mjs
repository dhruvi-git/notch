// Mock portfolio and hiring-drive endpoints for scripts/mock-api.mjs. Development only.
// Scoring follows the real API's formulas (engine SkillScoring and MatchScoring) closely enough
// to make the screens behave; the real algorithms run in Java.

import { randomUUID } from "node:crypto";

const daysAgo = (d) => new Date(Date.now() - d * 86_400_000).toISOString();

const SKILLS = [
  [1, "Python", "Languages"], [2, "Java", "Languages"], [3, "JavaScript", "Languages"], [4, "TypeScript", "Languages"],
  [17, "SQL", "Data"], [20, "pandas", "Data"], [22, "Excel", "Data"], [23, "Power BI", "Data"], [24, "Tableau", "Data"],
  [25, "Statistics", "Data"], [26, "Data Visualization", "Data"], [40, "Machine Learning", "AI & ML"],
  [42, "scikit-learn", "AI & ML"], [44, "PyTorch", "AI & ML"], [60, "React", "Web"], [61, "Next.js", "Web"],
  [64, "Node.js", "Web"], [67, "CSS", "Web"], [72, "Spring Boot", "Web"], [73, "REST APIs", "Web"],
  [90, "PostgreSQL", "Databases"], [102, "Docker", "DevOps & Cloud"], [100, "Git", "DevOps & Cloud"],
].map(([id, name, domain]) => ({ id, name, domain }));
const byName = new Map(SKILLS.map((s) => [s.name.toLowerCase(), s]));
const byId = new Map(SKILLS.map((s) => [s.id, s]));
const LEVELS = ["CLAIMED", "SHOWN", "VERIFIED"];

/** A sample pool of public candidates: handle, headline, and skill → [score, level]. */
const POOL = [
  ["asha-r", "Data analyst · Pune", { SQL: [91, "VERIFIED"], Python: [84, "VERIFIED"], pandas: [78, "VERIFIED"], "Power BI": [58, "SHOWN"], Statistics: [46, "SHOWN"] }],
  ["kabir-dev", "Data + backend", { SQL: [88, "VERIFIED"], Python: [82, "VERIFIED"], Java: [70, "VERIFIED"], "Spring Boot": [61, "VERIFIED"], Statistics: [60, "SHOWN"] }],
  ["meera-s", "Analytics, BI", { SQL: [72, "VERIFIED"], Python: [63, "SHOWN"], "Power BI": [81, "VERIFIED"], Statistics: [52, "SHOWN"] }],
  ["rohan-k", "ML engineer", { Python: [90, "VERIFIED"], SQL: [66, "VERIFIED"], "scikit-learn": [77, "VERIFIED"], "Machine Learning": [74, "VERIFIED"], Statistics: [80, "SHOWN"] }],
  ["tanvi-j", "Data analyst", { SQL: [75, "VERIFIED"], Python: [57, "VERIFIED"], "Power BI": [45, "SHOWN"], Statistics: [36, "SHOWN"] }],
  ["arjun-m", "Backend developer", { Java: [74, "VERIFIED"], "Spring Boot": [60, "VERIFIED"], PostgreSQL: [55, "SHOWN"], Docker: [40, "SHOWN"] }],
  ["zoya-a", "Frontend developer", { JavaScript: [86, "VERIFIED"], React: [83, "VERIFIED"], TypeScript: [70, "VERIFIED"], CSS: [65, "SHOWN"] }],
].map(([handle, headline, skills]) => ({ userId: randomUUID(), handle, headline, skills }));

const drives = []; // { id, ownerId, title, description, createdAt, roles, run }
const portfolios = new Map(); // userId -> { profile, evidence: [], userSkills: Map }

function portfolio(user) {
  if (!portfolios.has(user.id)) portfolios.set(user.id, { profile: null, evidence: [], hidden: new Set() });
  return portfolios.get(user.id);
}

function score(evidence) {
  let miss = 1;
  for (const e of evidence) {
    const months = (Date.now() - Date.parse(e.lastActive)) / (30.44 * 86_400_000);
    const strength = e.kind === "GITHUB_REPO"
      ? (e.fork || e.duplicate ? 0 : 0.85 * (0.5 * Math.min(1, e.ownCommits / 50) + 0.5 * Math.min(1, e.activeWeeks / 12)))
      : e.kind === "PROJECT" ? 0.4 : 0.2 * (e.depth ?? 0.5);
    miss *= 1 - strength * Math.pow(0.5, Math.max(0, months) / 12);
  }
  return Math.round(1000 * (1 - miss)) / 10;
}

function profileView(p, owner) {
  const bySkill = new Map();
  for (const e of p.evidence) for (const s of e.skills) {
    if (!bySkill.has(s.id)) bySkill.set(s.id, []);
    bySkill.get(s.id).push(e);
  }
  const skills = [...bySkill.entries()].filter(([id]) => !p.hidden.has(id)).map(([id, ev]) => {
    const sk = byId.get(id);
    const verified = ev.some((e) => e.kind === "GITHUB_REPO" && !e.fork && !e.duplicate && e.ownCommits >= 5 && e.activeWeeks >= 2);
    const s = score(ev);
    return { id, name: sk.name, domain: sk.domain, score: s, level: verified ? "VERIFIED" : "SHOWN",
      topPercent: s >= 90 ? 10 : s >= 75 ? 25 : null, evidenceIds: ev.map((e) => e.id) };
  }).sort((a, b) => b.score - a.score || a.name.localeCompare(b.name));
  const evidence = p.evidence
    .filter((e) => owner || e.skills.length > 0)
    .map((e) => ({ ...e, pendingSkills: owner ? e.pendingSkills : [] }));
  const pr = p.profile;
  return { hasProfile: Boolean(pr), handle: pr?.handle ?? null, headline: pr?.headline ?? null, domain: null,
    isPublic: pr?.isPublic ?? false, skills, evidence, poolSize: POOL.length + 1 };
}

function match(reqs, skills) {
  const total = reqs.reduce((n, r) => n + r.weight, 0);
  let sum = 0;
  const missing = [];
  const terms = reqs.map((r) => {
    const have = skills[r.skill];
    if (!have || have[0] === 0) {
      if (r.mustHave) missing.push(r.skill);
      return { skill: r.skill, weight: r.weight, skillScore: 0, level: null, levelFactor: 0, contribution: 0 };
    }
    const g = LEVELS.indexOf(have[1]) >= LEVELS.indexOf(r.minLevel) ? 1 : 0.5;
    const c = (r.weight * have[0] * g) / total;
    sum += c;
    return { skill: r.skill, weight: r.weight, skillScore: have[0], level: have[1], levelFactor: g, contribution: Math.round(c * 100) / 100 };
  });
  return { score: missing.length ? 0 : Math.round(sum * 100) / 100, eligible: missing.length === 0, terms };
}

/** Kuhn's augmenting-path matching, the unit-capacity case of max-flow, with seats expanded. */
function assign(roles) {
  const seats = [];
  roles.forEach((r, j) => { for (let k = 0; k < r.seats; k++) seats.push(j); });
  const scores = POOL.map((c) => roles.map((r) => match(r.requirements, c.skills)));
  const ok = (i, j) => scores[i][j].eligible && scores[i][j].score >= roles[j].threshold;
  // Greedy, for the comparison.
  const pairs = [];
  POOL.forEach((_, i) => roles.forEach((__, j) => ok(i, j) && pairs.push([i, j])));
  pairs.sort((a, b) => scores[b[0]][b[1]].score - scores[a[0]][a[1]].score);
  const left = roles.map((r) => r.seats), used = new Set();
  let greedy = 0;
  for (const [i, j] of pairs) if (!used.has(i) && left[j] > 0) { used.add(i); left[j]--; greedy++; }
  // Max matching, best scores tried first.
  const seatOf = new Array(seats.length).fill(-1);
  const tryCand = (i, seen) => {
    const order = seats.map((j, s) => s).filter((s) => ok(i, seats[s])).sort((a, b) => scores[i][seats[b]].score - scores[i][seats[a]].score);
    for (const s of order) {
      if (seen.has(s)) continue;
      seen.add(s);
      if (seatOf[s] === -1 || tryCand(seatOf[s], seen)) { seatOf[s] = i; return true; }
    }
    return false;
  };
  let paths = 0;
  [...POOL.keys()].sort((a, b) => Math.max(...roles.map((_, j) => scores[b][j].score)) - Math.max(...roles.map((_, j) => scores[a][j].score)))
    .forEach((i) => { if (tryCand(i, new Set())) paths++; });
  return {
    runAt: new Date().toISOString(),
    totalSeats: seats.length,
    filled: seatOf.filter((x) => x !== -1).length,
    greedyFilled: greedy,
    augmentingPaths: Math.max(0, seatOf.filter((x) => x !== -1).length - greedy),
    millis: 0.4,
    roles: roles.map((r, j) => {
      const placements = seats.map((jj, s) => (jj === j && seatOf[s] !== -1 ? seatOf[s] : -1)).filter((i) => i !== -1)
        .map((i) => ({ userId: POOL[i].userId, handle: POOL[i].handle, score: scores[i][j].score }))
        .sort((a, b) => b.score - a.score);
      return { roleId: r.id, name: r.name, seats: r.seats, filled: placements.length,
        eligible: POOL.filter((_, i) => ok(i, j)).length, placements };
    }),
  };
}

function parseJd(text) {
  const reqs = new Map();
  for (const sentence of text.split(/[\n;•]+|(?<=[.!?])\s+/)) {
    const lower = sentence.toLowerCase();
    const kind = /nice to have|a plus|bonus|preferred/.test(lower) ? 1 : /must|required|strong|need/.test(lower) ? 3 : 2;
    for (const s of SKILLS) {
      if (new RegExp(`(^|[^a-z])${s.name.toLowerCase().replace(/[.+]/g, "\\$&")}([^a-z]|$)`).test(lower)) {
        reqs.set(s.id, Math.max(reqs.get(s.id) ?? 0, kind));
      }
    }
  }
  return [...reqs.entries()].map(([id, k]) => ({ skillId: id, skill: byId.get(id).name, weight: k,
    mustHave: k === 3, minLevel: k === 3 ? "VERIFIED" : "SHOWN" })).sort((a, b) => b.weight - a.weight);
}

const driveView = ({ ownerId, run, ...d }) => d;

/** Public routes (no sign-in). Returns true when handled. */
export function handlePublic(path, res, send, problem) {
  const m = path.match(/^\/public\/profiles\/([^/]+)$/);
  if (!m) return false;
  const handle = decodeURIComponent(m[1]);
  for (const p of portfolios.values()) {
    if (p.profile?.handle === handle && p.profile.isPublic) { send(res, 200, profileView(p, false)); return true; }
  }
  const sample = POOL.find((c) => c.handle === handle);
  if (sample) {
    const skills = Object.entries(sample.skills).map(([name, [s, level]]) => {
      const sk = byName.get(name.toLowerCase());
      return { id: sk.id, name, domain: sk.domain, score: s, level, topPercent: s >= 90 ? 10 : s >= 80 ? 25 : null, evidenceIds: [] };
    }).sort((a, b) => b.score - a.score);
    send(res, 200, { hasProfile: true, handle, headline: sample.headline, domain: null, isPublic: true, skills, evidence: [], poolSize: POOL.length });
    return true;
  }
  problem(res, 404, "not_found", `A public profile called @${handle} was not found.`);
  return true;
}

/** Signed-in routes. Returns true when handled. */
export async function handle(req, res, user, path, url, { send, problem, readBody }) {
  const json = async () => JSON.parse((await readBody(req)).toString() || "{}");
  const p = portfolio(user);

  if (path === "/api/skills/autocomplete") {
    const q = (url.searchParams.get("q") ?? "").trim().toLowerCase();
    send(res, 200, q ? SKILLS.filter((s) => s.name.toLowerCase().startsWith(q)).slice(0, 10).map(({ id, name }) => ({ id, name })) : []);
    return true;
  }
  if (path === "/api/portfolio/me" && req.method === "GET") { send(res, 200, profileView(p, true)); return true; }
  if (path === "/api/portfolio/me" && req.method === "PUT") {
    const b = await json();
    const h = String(b.handle ?? "").trim().toLowerCase();
    if (!/^[a-z0-9][a-z0-9-]{1,38}$/.test(h)) { problem(res, 400, "invalid_handle", "A handle is 2 to 39 characters: lower-case letters, digits and hyphens."); return true; }
    if (POOL.some((c) => c.handle === h)) { problem(res, 409, "handle_taken", `@${h} is already taken. Try another handle.`); return true; }
    p.profile = { handle: h, headline: b.headline ?? null, isPublic: Boolean(b.isPublic) };
    send(res, 200, profileView(p, true));
    return true;
  }
  if (path === "/api/portfolio/github/sync" && req.method === "POST") {
    const b = await json();
    const username = b.username || user.handle;
    p.evidence = p.evidence.filter((e) => e.kind !== "GITHUB_REPO");
    const repo = (title, desc, ownCommits, activeWeeks, days, names) => ({ id: randomUUID(), kind: "GITHUB_REPO", title,
      url: `https://github.com/${username}/${title}`, description: desc, ownCommits, activeWeeks, fork: false, duplicate: false,
      lastActive: daysAgo(days), skills: names.map((n) => ({ id: byName.get(n.toLowerCase()).id, name: n })), pendingSkills: [] });
    p.evidence.push(
      repo("sales-dashboard", "Monthly sales analysis for a local retailer, with a Power BI report.", 146, 9, 3, ["SQL", "Python", "pandas", "Power BI"]),
      repo("churn-model", "Predicting subscription churn with scikit-learn.", 61, 5, 60, ["Python", "scikit-learn", "Machine Learning"]),
    );
    send(res, 200, { username, listed: 5, read: 4, added: 2, updated: 0, removed: 0, forksSkipped: 1, withoutOwnCommits: 2,
      duplicates: [], partial: false, note: null });
    return true;
  }
  if (path === "/api/portfolio/chat-export" && req.method === "POST") {
    await readBody(req);
    p.evidence = p.evidence.filter((e) => e.kind !== "CHAT_EXPORT");
    const suggestions = [["Statistics", 14, 0.72], ["Machine Learning", 6, 0.4]].map(([name, convs, depth]) => {
      const e = { id: randomUUID(), kind: "CHAT_EXPORT", title: `Discussed ${name} in ${convs} ChatGPT conversations`, url: null,
        description: null, ownCommits: 0, activeWeeks: 0, fork: false, duplicate: false, lastActive: daysAgo(4), depth,
        skills: [], pendingSkills: [{ id: byName.get(name.toLowerCase()).id, name }] };
      p.evidence.push(e);
      return { evidenceId: e.id, skillId: e.pendingSkills[0].id, skill: name, conversations: convs, messages: convs * 2, depth };
    });
    send(res, 200, { format: "chatgpt", conversations: 218, suggestions });
    return true;
  }
  if (path === "/api/portfolio/evidence" && req.method === "POST") {
    const b = await json();
    const skills = (b.skills ?? []).map((n) => byName.get(String(n).toLowerCase())).filter(Boolean);
    if (!b.title?.trim()) { problem(res, 400, "validation", "Give the project a title."); return true; }
    if (skills.length === 0) { problem(res, 400, "no_skills", "Add at least one skill this project shows."); return true; }
    const e = { id: randomUUID(), kind: "PROJECT", title: b.title.trim(), url: b.url ?? null, description: b.description ?? null,
      ownCommits: 0, activeWeeks: 0, fork: false, duplicate: false, lastActive: new Date().toISOString(),
      skills: skills.map(({ id, name }) => ({ id, name })), pendingSkills: [] };
    p.evidence.push(e);
    send(res, 201, e);
    return true;
  }
  let m = path.match(/^\/api\/portfolio\/evidence\/([^/]+)$/);
  if (m && req.method === "DELETE") {
    const before = p.evidence.length;
    p.evidence = p.evidence.filter((e) => e.id !== m[1]);
    if (p.evidence.length === before) problem(res, 404, "not_found", "That evidence was not found.");
    else send(res, 204);
    return true;
  }
  m = path.match(/^\/api\/portfolio\/evidence\/([^/]+)\/skills\/(\d+)$/);
  if (m && req.method === "PATCH") {
    const e = p.evidence.find((x) => x.id === m[1]);
    const s = e?.pendingSkills.find((x) => x.id === Number(m[2]));
    if (!s) { problem(res, 404, "not_found", "That suggested skill was not found."); return true; }
    e.pendingSkills = e.pendingSkills.filter((x) => x !== s);
    e.skills.push(s);
    send(res, 200, profileView(p, true));
    return true;
  }

  if (path === "/api/drives" && req.method === "GET") {
    send(res, 200, drives.filter((d) => d.ownerId === user.id).toReversed().map(({ id, title, createdAt }) => ({ id, title, createdAt })));
    return true;
  }
  if (path === "/api/drives" && req.method === "POST") {
    const b = await json();
    if (!b.title?.trim() || !b.description?.trim()) { problem(res, 400, "empty_description", "Paste the job description to start a drive."); return true; }
    const d = { id: randomUUID(), ownerId: user.id, title: b.title.trim(), description: b.description.trim(), createdAt: new Date().toISOString(),
      roles: [{ id: randomUUID(), name: b.title.trim(), seats: 1, threshold: 50, requirements: parseJd(b.description) }], run: null };
    drives.push(d);
    send(res, 201, driveView(d));
    return true;
  }
  m = path.match(/^\/api\/drives\/([^/]+)(\/.*)?$/);
  if (m) {
    const d = drives.find((x) => x.id === m[1] && x.ownerId === user.id);
    if (!d) { problem(res, 404, "not_found", "That drive was not found."); return true; }
    const rest = m[2] ?? "";
    if (rest === "" && req.method === "GET") { send(res, 200, driveView(d)); return true; }
    if (rest === "" && req.method === "DELETE") { drives.splice(drives.indexOf(d), 1); send(res, 204); return true; }
    if (rest === "/roles" && req.method === "PUT") {
      const b = await json();
      d.roles = (b.roles ?? []).map((r) => ({ id: randomUUID(), name: r.name, seats: r.seats, threshold: r.threshold,
        requirements: r.requirements.map((q) => ({ ...q, skill: byId.get(q.skillId)?.name ?? "?" })) }));
      d.run = null;
      send(res, 200, driveView(d));
      return true;
    }
    const sm = rest.match(/^\/roles\/([^/]+)\/shortlist$/);
    if (sm) {
      const role = d.roles.find((r) => r.id === sm[1]);
      if (!role) { problem(res, 404, "not_found", "That role was not found."); return true; }
      const k = Number(url.searchParams.get("k") ?? 20);
      const ranked = POOL.map((c) => ({ c, m: match(role.requirements, c.skills) })).filter((x) => x.m.eligible && x.m.score > 0)
        .sort((a, b) => b.m.score - a.m.score || a.c.handle.localeCompare(b.c.handle));
      send(res, 200, { roleId: role.id, role: role.name, poolSize: POOL.length, eligible: ranked.length,
        candidates: ranked.slice(0, k).map(({ c, m }) => ({ userId: c.userId, handle: c.handle, headline: c.headline, score: m.score, breakdown: m.terms })) });
      return true;
    }
    if (rest === "/assign" && req.method === "POST") { d.run = assign(d.roles); send(res, 200, d.run); return true; }
    if (rest === "/assignment" && req.method === "GET") {
      if (!d.run) problem(res, 404, "no_assignment", "Seats have not been assigned for this drive yet.");
      else send(res, 200, { ...d.run, roles: d.run.roles.map((r) => ({ ...r, eligible: null })) });
      return true;
    }
  }
  return false;
}
