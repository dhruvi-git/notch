// Mock portfolio and hiring-drive endpoints for scripts/mock-api.mjs. Development only.
// Scoring follows the real API's formulas (engine SkillScoring and MatchScoring) closely enough
// to make the screens behave; the real algorithms run in Java.

import { randomUUID } from "node:crypto";

import { MEMORY_PROMPT, parseMemory, readOf } from "./mock-insights.mjs";

const daysAgo = (d) => new Date(Date.now() - d * 86_400_000).toISOString();

const SKILLS = [
  [1, "Python", "Languages"], [2, "Java", "Languages"], [3, "JavaScript", "Languages"], [4, "TypeScript", "Languages"],
  [17, "SQL", "Data"], [20, "pandas", "Data"], [22, "Excel", "Data"], [23, "Power BI", "Data"], [24, "Tableau", "Data"],
  [25, "Statistics", "Data"], [26, "Data Visualization", "Data"], [40, "Machine Learning", "AI & ML"],
  [42, "scikit-learn", "AI & ML"], [44, "PyTorch", "AI & ML"], [60, "React", "Web"], [61, "Next.js", "Web"],
  [64, "Node.js", "Web"], [67, "CSS", "Web"], [72, "Spring Boot", "Web"], [73, "REST APIs", "Web"],
  [90, "PostgreSQL", "Databases"], [102, "Docker", "DevOps & Cloud"], [100, "Git", "DevOps & Cloud"],
  [5, "C++", "Languages"], [110, "Data Structures", "CS Fundamentals"], [111, "Algorithms", "CS Fundamentals"],
  [45, "Deep Learning", "AI & ML"], [104, "AWS", "DevOps & Cloud"],
].map(([id, name, domain]) => ({ id, name, domain }));
const byName = new Map(SKILLS.map((s) => [s.name.toLowerCase(), s]));
const byId = new Map(SKILLS.map((s) => [s.id, s]));
const LEVELS = ["CLAIMED", "SHOWN", "VERIFIED", "DEFENDED"];

/** A sample pool of public candidates: handle, headline, and skill → [score, level]. */
const POOL = [
  ["asha-r", "Data analyst · Pune", { SQL: [93, "DEFENDED"], Python: [88, "DEFENDED"], pandas: [78, "VERIFIED"], "Power BI": [58, "SHOWN"], Statistics: [46, "SHOWN"] }],
  ["kabir-dev", "Data + backend", { SQL: [88, "VERIFIED"], Python: [82, "VERIFIED"], Java: [70, "VERIFIED"], "Spring Boot": [61, "VERIFIED"], Statistics: [60, "SHOWN"] }],
  ["meera-s", "Analytics, BI", { SQL: [72, "VERIFIED"], Python: [63, "SHOWN"], "Power BI": [81, "VERIFIED"], Statistics: [52, "SHOWN"] }],
  ["rohan-k", "ML engineer", { Python: [92, "DEFENDED"], SQL: [66, "VERIFIED"], "scikit-learn": [77, "VERIFIED"], "Machine Learning": [74, "VERIFIED"], Statistics: [80, "SHOWN"] }],
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
      ? (e.fork || e.duplicate ? 0 : (e.ownership?.defended ? 1 : 0.85) * (0.5 * Math.min(1, e.ownCommits / 50) + 0.5 * Math.min(1, e.activeWeeks / 12)))
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
    const defended = ev.some((e) => e.kind === "GITHUB_REPO" && !e.duplicate && e.ownership?.defended);
    const s = score(ev);
    return { id, name: sk.name, domain: sk.domain, score: s, level: defended ? "DEFENDED" : verified ? "VERIFIED" : "SHOWN",
      topPercent: s >= 90 ? 10 : s >= 75 ? 25 : null, evidenceIds: ev.map((e) => e.id) };
  }).sort((a, b) => b.score - a.score || a.name.localeCompare(b.name));
  const evidence = p.evidence
    .filter((e) => owner || e.skills.length > 0)
    .map(({ depth, ...e }) => ({ ...e, ownership: e.ownership ?? null, pendingSkills: owner ? e.pendingSkills : [] }));
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


// ------------------------------------------------------------------ Ownership Checks

const checks = new Map();

/** Sample regions from the mock repositories, with the concept and rubric the real planner would attach. */
const QUESTIONS = {
  "sales-dashboard": [
    { path: "etl/monthly_sales.py", startLine: 18, endLine: 41, language: "python", concept: "Joins", covers: ["SQL", "pandas", "etl/monthly_sales.py"],
      prompt: "In monthly_sales, you merge orders with stores. Why is it a left merge on store_id, and what would the report show if you made it an inner merge?",
      rubric: [["left", "keep"], ["missing", "unmatched", "nan", "null"], ["inner", "drop", "lose"], ["store_id", "key"]],
      code: `def monthly_sales(orders: pd.DataFrame, stores: pd.DataFrame) -> pd.DataFrame:
    """Revenue per store per month, including stores that sold nothing."""
    orders = orders.assign(month=orders["ordered_at"].dt.to_period("M"))
    merged = stores.merge(orders, on="store_id", how="left")
    merged["revenue"] = merged["quantity"].fillna(0) * merged["unit_price"].fillna(0)

    by_month = (
        merged.groupby(["store_id", "store_name", "month"], dropna=False)["revenue"]
        .sum()
        .reset_index()
    )
    # A store with no orders still gets a row, so the dashboard shows a zero, not a gap.
    by_month["month"] = by_month["month"].fillna(orders["month"].max())
    return by_month.sort_values(["month", "revenue"], ascending=[True, False])` },
    { path: "sql/top_products.sql", startLine: 1, endLine: 17, language: "sql", concept: "Aggregation", covers: ["SQL", "sql/top_products.sql"],
      prompt: "Walk me through top_products.sql. Why does the ranking use a window function instead of ORDER BY with LIMIT?",
      rubric: [["window", "over", "partition"], ["per category", "each category", "category"], ["limit", "whole table", "only"], ["rank", "dense_rank", "ties"]],
      code: `WITH product_revenue AS (
    SELECT p.category, p.name, SUM(oi.quantity * oi.unit_price) AS revenue
    FROM order_items oi
    JOIN products p ON p.id = oi.product_id
    WHERE oi.ordered_at >= date_trunc('quarter', now())
    GROUP BY p.category, p.name
),
ranked AS (
    SELECT *, DENSE_RANK() OVER (PARTITION BY category ORDER BY revenue DESC) AS place
    FROM product_revenue
)
SELECT category, name, revenue
FROM ranked
WHERE place <= 3
ORDER BY category, place;` },
    { path: "etl/clean.py", startLine: 7, endLine: 25, language: "python", concept: "Null handling", covers: ["Python", "etl/clean.py"],
      prompt: "clean_orders drops some rows and fills others. How did you decide which missing values to drop and which to fill?",
      rubric: [["drop", "dropna"], ["fill", "fillna", "default"], ["price", "quantity", "store"], ["why", "because", "otherwise"]],
      code: `def clean_orders(raw: pd.DataFrame) -> pd.DataFrame:
    df = raw.copy()
    # An order without a store or a timestamp can't be placed on the report at all.
    df = df.dropna(subset=["store_id", "ordered_at"])
    df["ordered_at"] = pd.to_datetime(df["ordered_at"], errors="coerce", utc=True)
    df = df[df["ordered_at"].notna()]

    # Quantity missing means the till didn't record it: one item is the safe default.
    df["quantity"] = df["quantity"].fillna(1).astype(int)
    df = df[df["quantity"] > 0]

    # Price comes from the catalogue when the till didn't send it.
    df["unit_price"] = df["unit_price"].fillna(df["sku"].map(CATALOGUE_PRICE))
    return df.drop_duplicates(subset=["order_id", "sku"])` },
    { path: "etl/load.py", startLine: 30, endLine: 44, language: "python", concept: "Error handling", covers: ["Python", "etl/load.py"],
      prompt: "In load_month, what happens if the upload to the warehouse fails halfway, and why did you write it that way?",
      rubric: [["transaction", "rollback", "commit"], ["retry", "again"], ["partial", "halfway", "duplicate"], ["log", "raise"]],
      code: `def load_month(conn, frame: pd.DataFrame, month: str) -> int:
    with conn.begin():  # all or nothing: a half-loaded month is worse than none
        conn.execute(text("DELETE FROM sales_monthly WHERE month = :m"), {"m": month})
        rows = frame.to_dict("records")
        for attempt in range(3):
            try:
                conn.execute(insert(sales_monthly), rows)
                break
            except OperationalError as err:
                log.warning("load %s failed (attempt %d): %s", month, attempt + 1, err)
                time.sleep(2 ** attempt)
        else:
            raise LoadError(f"could not load {month}")
    return len(rows)` },
    { path: "dashboard/measures.py", startLine: 5, endLine: 16, language: "python", concept: "Lookups", covers: ["Power BI", "dashboard/measures.py"],
      prompt: "growth_vs_last_year looks up last year's revenue. What does it return for a store that opened this year, and why?",
      rubric: [["none", "null", "nan", "missing"], ["new store", "opened", "no history"], ["divide", "division", "zero"], ["lookup", "dict", "get"]],
      code: `def growth_vs_last_year(current: dict[str, float], previous: dict[str, float]) -> dict[str, float | None]:
    growth = {}
    for store, revenue in current.items():
        before = previous.get(store)
        if not before:  # new stores and stores with no sales have nothing to compare with
            growth[store] = None
            continue
        growth[store] = round((revenue - before) / before * 100, 1)
    return growth` },
  ],
};
QUESTIONS["churn-model"] = QUESTIONS["sales-dashboard"];

function gradeAnswer(q) {
  const a = (q.answer ?? "").toLowerCase();
  const words = a.split(/\s+/).filter(Boolean).length;
  if (words === 0) return { score: 0, feedback: "Not answered.", flags: [] };
  const hit = q.rubric.filter((g) => g.some((t) => a.includes(t)));
  const ids = q.code.match(/[A-Za-z_]\w{3,}/g) ?? [];
  const named = new Set(ids.filter((id) => a.includes(id.toLowerCase()))).size;
  let score = Math.round(50 * hit.length / q.rubric.length + 30 * Math.min(1, named / 2) + 20 * Math.min(1, words / 70));
  if (words < 8) score = Math.min(score, 10);
  const missed = q.rubric.filter((g) => !hit.includes(g)).map((g) => g[0]);
  const feedback = score >= 70
    ? `Clear and specific: covered ${hit.map((g) => g[0]).join(", ")} and referred to the code by name.`
    : `Covered ${hit.length ? hit.map((g) => g[0]).join(", ") : "none of the key ideas"}. Missing: ${missed.join(", ")}.`;
  const flags = [];
  if (q.pastes) flags.push(q.pastes === 1 ? "Pasted text once" : `Pasted text ${q.pastes} times`);
  if (q.focusLost >= 2) flags.push(`Left the page ${q.focusLost} times`);
  if (words >= 40 && q.millis && words / (q.millis / 60000) > 120) flags.push("Answered at over 120 words a minute");
  return { score, feedback, flags };
}

function finishCheck(c, p) {
  let pasted = 0;
  for (const q of c.questions) {
    Object.assign(q, gradeAnswer(q));
    if (q.pastes) pasted++;
  }
  c.score = Math.round(c.questions.reduce((n, q) => n + q.score, 0) / c.questions.length);
  c.defended = c.score >= 70 && pasted <= 1;
  c.summary = c.defended
    ? `Explained ${c.questions.filter((q) => q.score >= 70).length} of ${c.questions.length} parts of the code clearly, naming its own functions and choices.`
    : pasted > 1 ? "More than one answer was pasted in, so this check can't defend the repository." : "Some answers were too thin to show the code is understood. Another attempt is open in 24 hours.";
  c.status = "FINISHED";
  c.finishedAt = new Date().toISOString();
  c.gradedBy = "rubric";
  const e = p.evidence.find((x) => x.id === c.evidenceId);
  const best = e?.ownership;
  const attempts = (best?.attempts ?? 0) + 1;
  if (e && (!best || (c.defended && !best.defended) || (c.defended === best.defended && c.score > best.score))) {
    e.ownership = { checkId: c.id, score: c.score, defended: c.defended, finishedAt: c.finishedAt, attempts };
  } else if (e) e.ownership = { ...best, attempts };
}

function checkView(c, handle = null) {
  const done = c.status === "FINISHED";
  const { userId, questions, ...rest } = c;
  return { ...rest, handle, secondsLeft: done ? 0 : Math.max(0, Math.round((Date.parse(c.deadline) - Date.now()) / 1000)),
    questions: questions.map(({ rubric, pastes, focusLost, ...q }) => ({ ...q, score: done ? q.score : null, feedback: done ? q.feedback : null, flags: done ? q.flags : [] })) };
}

/** Public routes (no sign-in). Returns true when handled. */
export function handlePublic(path, res, send, problem) {
  const om = path.match(/^\/public\/ownership\/([^/]+)$/);
  if (om) {
    const c = checks.get(om[1]);
    const p = c && portfolios.get(c.userId);
    if (!c || c.status !== "FINISHED" || !p?.profile?.isPublic) { problem(res, 404, "not_found", "A public Ownership Check was not found."); return true; }
    send(res, 200, checkView(c, p.profile.handle));
    return true;
  }
  const rm = path.match(/^\/public\/profiles\/([^/]+)\/read$/);
  if (rm) {
    const owner = [...portfolios.values()].find((x) => x.profile?.handle === rm[1] && x.profile.isPublic);
    if (!owner) { problem(res, 404, "not_found", `A public profile called @${rm[1]} was not found.`); return true; }
    send(res, 200, readOf(profileView(owner, false), owner.evidence, new Map(SKILLS.map((k) => [k.id, k.name])), false));
    return true;
  }
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
    // Commit rhythm: mostly late evenings, a Wednesday habit, some weekends (deterministic per repository).
    const rhythm = (commits, seed) => {
      const weekdays = Array(7).fill(0), hours = Array(24).fill(0);
      const dayW = [1.0, 1.1, 2.4, 1.2, 0.9, 1.3, 1.1], hourW = Array.from({ length: 24 }, (_, h) => (h >= 21 || h <= 1 ? 3.2 : h >= 14 && h <= 18 ? 1.6 : h >= 9 ? 0.8 : 0.15));
      const pick = (w, r) => { const t = w.reduce((a, b) => a + b, 0); let x = r * t; for (let i = 0; i < w.length; i++) { x -= w[i]; if (x <= 0) return i; } return w.length - 1; };
      let r = seed;
      const rnd = () => ((r = (r * 9301 + 49297) % 233280) / 233280);
      for (let i = 0; i < commits; i++) { weekdays[pick(dayW, rnd())]++; hours[pick(hourW, rnd())]++; }
      return { weekdays, hours };
    };
    const repo = (title, desc, ownCommits, activeWeeks, days, startedDays, names, seed) => ({ id: randomUUID(), kind: "GITHUB_REPO", title,
      url: `https://github.com/${username}/${title}`, description: desc, ownCommits, activeWeeks, fork: false, duplicate: false,
      lastActive: daysAgo(days), firstActive: daysAgo(startedDays), ...rhythm(ownCommits, seed),
      skills: names.map((n) => ({ id: byName.get(n.toLowerCase()).id, name: n })), pendingSkills: [] });
    p.evidence.push(
      repo("sales-dashboard", "Monthly sales analysis for a local retailer, with a Power BI report.", 146, 9, 3, 140, ["SQL", "Python", "pandas", "Power BI", "Data Visualization"], 11),
      repo("churn-model", "Predicting subscription churn with scikit-learn.", 61, 5, 60, 200, ["Python", "scikit-learn", "Machine Learning", "pandas"], 23),
      repo("fest-tickets", "Ticket sales for the college fest: a React front end on a Node API and PostgreSQL.", 48, 4, 120, 260, ["React", "Node.js", "PostgreSQL", "SQL", "JavaScript"], 37),
      repo("etl-pipeline", "Nightly ETL from Google Sheets into PostgreSQL.", 34, 6, 25, 90, ["Python", "PostgreSQL", "SQL", "Git"], 41),
      repo("dsa-practice", "Data structures and algorithms practice in C++.", 88, 11, 15, 480, ["C++", "Data Structures", "Algorithms"], 53),
    );
    send(res, 200, { username, listed: 8, read: 7, added: 5, updated: 0, removed: 0, forksSkipped: 1, withoutOwnCommits: 1,
      duplicates: [], partial: false, note: null });
    return true;
  }
  if (path === "/api/insights/me" && req.method === "GET") {
    send(res, 200, { read: readOf(profileView(p, true), p.evidence, new Map(SKILLS.map((k) => [k.id, k.name])), true), goal: p.goal ?? null });
    return true;
  }
  if (path === "/api/insights/goal" && req.method === "PUT") {
    const b = await json();
    const g = String(b.goal ?? "").trim();
    if (!g || g.length > 80) { problem(res, 400, "invalid_goal", "Describe the role in 1 to 80 characters."); return true; }
    p.goal = g;
    send(res, 200, { read: readOf(profileView(p, true), p.evidence, new Map(SKILLS.map((k) => [k.id, k.name])), true), goal: p.goal });
    return true;
  }
  if (path === "/api/portfolio/memory-prompt" && req.method === "GET") {
    send(res, 200, { prompt: MEMORY_PROMPT });
    return true;
  }
  if (path === "/api/portfolio/memory-import" && req.method === "POST") {
    const b = await json();
    let parsed;
    try { parsed = parseMemory(String(b.text ?? ""), byName); } catch (e) { problem(res, 400, "unreadable_reply", e.message); return true; }
    if (!parsed.skills.length && !parsed.projects.length && !parsed.goal) {
      problem(res, 422, "nothing_found", "The reply had no skills, projects or goal that Notch recognises."); return true;
    }
    p.evidence = p.evidence.filter((e) => !(e.kind === "CHAT_EXPORT" && e.chatSource === "MEMORY"));
    const depth = { USED: 0.7, LEARNING: 0.5, ASKED: 0.3 };
    const label = { USED: "used", LEARNING: "learning", ASKED: "asked about" };
    const skills = parsed.skills.map(({ skill, mode, note }) => {
      const e = { id: randomUUID(), kind: "CHAT_EXPORT", chatSource: "MEMORY", chatMode: mode, chatConversations: 0, chatNote: note,
        title: `${skill.name}: ${label[mode]}, from AI memory`, url: null, description: null, ownCommits: 0, activeWeeks: 0,
        fork: false, duplicate: false, lastActive: new Date().toISOString(), depth: depth[mode], skills: [], pendingSkills: [{ id: skill.id, name: skill.name }] };
      p.evidence.push(e);
      return { evidenceId: e.id, skillId: skill.id, name: skill.name, mode, note };
    });
    const titles = new Set(p.evidence.filter((e) => e.kind === "PROJECT").map((e) => e.title.toLowerCase()));
    let skipped = 0;
    const projects = [];
    for (const pr of parsed.projects) {
      if (titles.has(pr.title.toLowerCase())) { skipped++; continue; }
      titles.add(pr.title.toLowerCase());
      const e = { id: randomUUID(), kind: "PROJECT", chatSource: "MEMORY", title: pr.title, url: null, description: pr.description,
        ownCommits: 0, activeWeeks: 0, fork: false, duplicate: false, lastActive: new Date().toISOString(), skills: [],
        pendingSkills: pr.skills.map(({ id, name }) => ({ id, name })) };
      p.evidence.push(e);
      projects.push({ evidenceId: e.id, title: pr.title, skills: pr.skills.map((k) => k.name) });
    }
    if (parsed.goal) p.goal = parsed.goal;
    send(res, 200, { skills, projects, skippedProjects: skipped, unrecognised: parsed.unrecognised, goal: parsed.goal });
    return true;
  }
  if (path === "/api/portfolio/chat-export" && req.method === "POST") {
    await readBody(req);
    p.evidence = p.evidence.filter((e) => !(e.kind === "CHAT_EXPORT" && e.chatSource !== "MEMORY"));
    const suggestions = [["Statistics", 14, 0.72], ["Docker", 9, 0.5], ["SQL", 7, 0.45], ["Deep Learning", 5, 0.35]].map(([name, convs, depth]) => {
      const e = { id: randomUUID(), kind: "CHAT_EXPORT", chatSource: "EXPORT", chatMode: "ASKED", chatConversations: convs,
        title: `Discussed ${name} in ${convs} ChatGPT conversations`, url: null,
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

  if (path === "/api/ownership/checks" && req.method === "POST") {
    const b = await json();
    const e = p.evidence.find((x) => x.id === b.evidenceId);
    if (!e) { problem(res, 404, "not_found", "That repository was not found."); return true; }
    if (e.kind !== "GITHUB_REPO") { problem(res, 400, "not_a_repository", "Ownership Checks are about code: pick one of your GitHub repositories."); return true; }
    const open = [...checks.values()].find((c) => c.userId === user.id && c.evidenceId === e.id && c.status === "OPEN");
    if (open) { send(res, 201, checkView(open)); return true; }
    const last = [...checks.values()].filter((c) => c.userId === user.id && c.evidenceId === e.id && c.status === "FINISHED")
      .sort((a, b) => b.finishedAt.localeCompare(a.finishedAt))[0];
    if (last && !process.env.MOCK_NO_WAIT && Date.now() - Date.parse(last.finishedAt) < 24 * 3_600_000) {
      problem(res, 409, "retake_later", "You can take another check on this repository 24 hours after the last one."); return true;
    }
    const bank = QUESTIONS[e.title] ?? QUESTIONS["sales-dashboard"];
    const c = { id: randomUUID(), userId: user.id, evidenceId: e.id, repo: e.title, repoUrl: e.url, status: "OPEN",
      startedAt: new Date().toISOString(), deadline: new Date(Date.now() + 12 * 60_000).toISOString(), finishedAt: null,
      score: null, defended: false, summary: null, questionsBy: "templates", gradedBy: null, planElements: 9, planCovered: 9,
      questions: bank.map((q, i) => ({ ...q, position: i, answer: null, millis: null, pastes: 0, focusLost: 0, score: null, feedback: null, flags: [] })) };
    checks.set(c.id, c);
    send(res, 201, checkView(c));
    return true;
  }
  let om = path.match(/^\/api\/ownership\/checks\/([^/]+)(\/finish|\/answers\/(\d+))?$/);
  if (om) {
    const c = checks.get(om[1]);
    if (!c || c.userId !== user.id) { problem(res, 404, "not_found", "That Ownership Check was not found."); return true; }
    if (c.status === "OPEN" && Date.now() > Date.parse(c.deadline) + 30_000) finishCheck(c, p);
    if (!om[2] && req.method === "GET") { send(res, 200, checkView(c)); return true; }
    if (om[2] === "/finish" && req.method === "POST") { if (c.status === "OPEN") finishCheck(c, p); send(res, 200, checkView(c)); return true; }
    if (om[3] && req.method === "PUT") {
      if (c.status !== "OPEN") { problem(res, 409, "check_finished", "This check is finished; answers can't change."); return true; }
      const b = await json();
      const q = c.questions[Number(om[3])];
      if (!q) { problem(res, 404, "not_found", "That question was not found."); return true; }
      Object.assign(q, { answer: String(b.answer ?? "").trim(), millis: Number(b.millis) || 0, pastes: Number(b.pastes) || 0, focusLost: Number(b.focusLost) || 0 });
      send(res, 200, checkView(c));
      return true;
    }
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
