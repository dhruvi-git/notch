// A stand-in for the Notch API, for working on the web app without the Java backend.
// Development only: data lives in memory and resets on restart.
//
//   npm run mock-api            # listens on :8080, like the real API
//   MOCK_PORT=8090 npm run mock-api
//
// Covers sign-in, projects, imports (with background processing), conversations and usage.
// Later phases add the remaining endpoints as their screens are built.

import { createServer } from "node:http";
import { randomUUID } from "node:crypto";

const PORT = Number(process.env.MOCK_PORT ?? 8080);
const now = () => new Date().toISOString();
const daysAgo = (d) => new Date(Date.now() - d * 86_400_000).toISOString();

const users = new Map();      // token -> user
const projects = [];          // { id, ownerId, name, isPrivate, createdAt }
const imports = [];           // ImportReport + ownerId
const conversations = [];     // ConversationSummary + projectId
const usage = new Map();      // userId -> { import, question }

function seed(user) {
  if (projects.some((p) => p.ownerId === user.id)) return;
  const p = { id: randomUUID(), ownerId: user.id, name: "Billing service", isPrivate: false, createdAt: daysAgo(34) };
  projects.push(p, { id: randomUUID(), ownerId: user.id, name: "Analytics pipeline", isPrivate: true, createdAt: daysAgo(3) });
  const base = { projectId: p.id, ownerId: user.id, status: "READY", error: null };
  imports.push(
    { ...base, id: randomUUID(), filename: "chatgpt-export.zip", format: "chatgpt", conversationsAdded: 2, conversationsUpdated: 0,
      messagesAdded: 11, messagesSkipped: 0, chunksAdded: 12, duplicateChunks: 0, bloomFalsePositives: 0, secretsRedacted: 1,
      memoriesExtracted: 7, createdAt: daysAgo(33), finishedAt: daysAgo(33) },
    { ...base, id: randomUUID(), filename: "pasted-billing-chat.txt", format: "text", conversationsAdded: 1, conversationsUpdated: 0,
      messagesAdded: 3, messagesSkipped: 0, chunksAdded: 0, duplicateChunks: 3, bloomFalsePositives: 0, secretsRedacted: 0,
      memoriesExtracted: 0, createdAt: daysAgo(20), finishedAt: daysAgo(20) },
    { ...base, id: randomUUID(), filename: "claude-conversations.json", format: "claude", conversationsAdded: 1, conversationsUpdated: 0,
      messagesAdded: 6, messagesSkipped: 0, chunksAdded: 6, duplicateChunks: 0, bloomFalsePositives: 1, secretsRedacted: 0,
      memoriesExtracted: 4, createdAt: daysAgo(1), finishedAt: daysAgo(1) },
  );
  conversations.push(
    { id: randomUUID(), projectId: p.id, source: "claude", title: "Auth refactor: sessions vs JWT", startedAt: daysAgo(1), messageCount: 6 },
    { id: randomUUID(), projectId: p.id, source: "text", title: "pasted-billing-chat", startedAt: daysAgo(20), messageCount: 3 },
    { id: randomUUID(), projectId: p.id, source: "chatgpt", title: "Rate limiting the public API", startedAt: daysAgo(26), messageCount: 4 },
    { id: randomUUID(), projectId: p.id, source: "chatgpt", title: "Billing service: choosing a database", startedAt: daysAgo(33), messageCount: 7 },
  );
}


// Search fixtures, taken from the sample conversations in /samples.
const CHUNKS = [
  { conv: "Billing service: choosing a database", role: "user", days: 33, decision: false,
    text: "We're building the billing service. Mongo or Postgres? We need invoices joined to customers and line items." },
  { conv: "Billing service: choosing a database", role: "assistant", days: 33, decision: false,
    text: "For relational data like invoices, customers and line items, Postgres fits better. You get joins, transactions and constraints. Mongo would push those joins into application code." },
  { conv: "Billing service: choosing a database", role: "user", days: 33, decision: true,
    text: "Let's go with Postgres instead of MongoDB because we need joins and real transactions for payments." },
  { conv: "Billing service: choosing a database", role: "user", days: 33, decision: false,
    text: "Running the app now:\nError: connect ECONNREFUSED 127.0.0.1:5432" },
  { conv: "Billing service: choosing a database", role: "assistant", days: 33, decision: false,
    text: "The issue is Postgres isn't running locally. Start it with `docker compose up -d` and check with `docker compose ps`. If it still fails, confirm the port mapping is 5432:5432." },
  { conv: "Rate limiting the public API", role: "assistant", days: 26, decision: false,
    text: "A token bucket per API key works well. Store buckets in Redis so limits hold across instances. Start with 60 requests per minute and a burst of 20." },
  { conv: "Rate limiting the public API", role: "user", days: 26, decision: true,
    text: "We decided to use a Redis token bucket rather than an in-memory limiter, since we run three instances behind the load balancer." },
  { conv: "Auth refactor: sessions vs JWT", role: "assistant", days: 1, decision: false,
    text: "With one first-party frontend, server-side sessions are simpler and safer: you can revoke them instantly and keep tokens out of JavaScript. JWTs shine when many services verify tokens independently." },
  { conv: "Auth refactor: sessions vs JWT", role: "user", days: 1, decision: true,
    text: "We chose server-side sessions instead of JWT because we need instant logout and only have one frontend." },
  { conv: "Auth refactor: sessions vs JWT", role: "assistant", days: 1, decision: false,
    text: "The problem is the session is undefined on the first render, before the fetch resolves. Guard it with optional chaining: session?.user, and render a loading state until the session request finishes." },
].map((c, i) => ({ ...c, id: `chunk-${i}`, sentAt: daysAgo(c.days) }));

const KEYS = ["ECONNREFUSED", "TypeError", "session?.user", "docker compose up -d", "src/db/pool.ts", "src/auth/session.ts",
  "Billing service: choosing a database", "Rate limiting the public API", "Auth refactor: sessions vs JWT", "X-RateLimit-Remaining"];

const STOP = new Set("a an the and or of to in on at for with by from about is are was were be do does did we our i you it this that what which how should would could can will use any".split(" "));
const DECIDE = /^(decide|decided|decision|decisions|chose|choose|pick|picked|settle|settled|agreed)$/;
const EXPAND = { database: ["postgre", "mysql", "mongo", "sqlite"], cach: ["redis"], auth: ["jwt", "session", "login"] };

function parseQuery(q) {
  const words = q.toLowerCase().split(/[^\p{L}\p{N}_./:?-]+/u).map((w) => w.replace(/^[.:/?-]+|[.:/?-]+$/g, "")).filter(Boolean);
  const decisionIntent = words.some((w) => DECIDE.test(w));
  const terms = [];
  for (const w of words) {
    if (DECIDE.test(w) || STOP.has(w) || w.length < 3) continue;
    let t = w;
    if (t.length >= 6) for (const suf of ["ing", "ed", "s"]) if (t.endsWith(suf) && t.length - suf.length >= 4) { t = t.slice(0, -suf.length); break; }
    if (!terms.includes(t)) terms.push(t);
  }
  const expansions = terms.flatMap((t) => EXPAND[t] ?? []).filter((e) => !terms.includes(e));
  return { terms, expansions, decisionIntent };
}

function spansOf(text, patterns) {
  const lower = text.toLowerCase(), out = [];
  for (const p of patterns) { let i = lower.indexOf(p); while (i !== -1) { out.push([i, i + p.length]); i = lower.indexOf(p, i + 1); } }
  return out;
}

function search(q) {
  const started = Date.now();
  const { terms, expansions, decisionIntent } = parseQuery(q);
  const all = [...terms, ...expansions];
  const scored = [];
  for (const c of CHUNKS) {
    const spans = spansOf(c.text, all);
    const exactRaw = spansOf(c.text, terms).length + 0.5 * spansOf(c.text, expansions).length;
    const include = spans.length > 0 || (decisionIntent && terms.length === 0 && c.decision);
    if (!include) continue;
    scored.push({ c, spans, exactRaw });
  }
  const maxExact = Math.max(1, ...scored.map((s) => s.exactRaw));
  const w = decisionIntent ? { exact: 0.3, semantic: 0.3, recency: 0.1, decision: 0.3 } : { exact: 0.4, semantic: 0.35, recency: 0.1, decision: 0.15 };
  const hits = scored.map(({ c, spans, exactRaw }) => {
    const why = {
      exact: w.exact * (exactRaw / maxExact),
      semantic: w.semantic * Math.min(1, 0.25 + 0.15 * spans.length),
      recency: w.recency * Math.pow(0.5, c.days / 14),
      decision: c.decision ? w.decision : 0,
    };
    const score = why.exact + why.semantic + why.recency + why.decision;
    return { source: { chunkId: c.id, messageId: c.id + "-m", conversationId: c.conv, conversationTitle: c.conv, role: c.role, sentAt: c.sentAt },
      text: c.text, highlights: spans, score, why, decision: c.decision, threadId: c.conv };
  }).sort((a, b) => b.score - a.score).slice(0, 10);
  return { query: q, terms, phrases: [], expansions, decisionIntent, since: null, hits,
    exactCandidates: scored.length, semanticCandidates: Math.min(5, CHUNKS.length), indexVersion: 3, tookMillis: Math.max(1, Date.now() - started) + 3 };
}

function ask(q) {
  const res = search(q);
  const contexts = res.hits.slice(0, 6);
  const patterns = [...res.terms, ...res.expansions];
  const cue = /decided|go with|chose|we'll use|settled/i;
  const picked = [];
  contexts.forEach((h, ci) => {
    for (const m of h.text.matchAll(/[^.!?\n]+[.!?]?/g)) {
      const sentence = m[0].trim();
      if (!sentence || sentence.endsWith("?")) continue;
      const lower = sentence.toLowerCase();
      const hitsTerms = patterns.some((p) => lower.includes(p));
      if (res.decisionIntent ? cue.test(sentence) && (hitsTerms || patterns.length === 0) : hitsTerms) {
        const start = h.text.indexOf(sentence);
        picked.push({ ci, sentence, start, end: start + sentence.length });
      }
    }
  });
  const chosen = picked.slice(0, 4);
  if (chosen.length === 0) {
    return { answer: "I couldn't find anything in this project that answers that. Try other words, or import the conversation where it was discussed.",
      grounded: false, citations: [], model: "offline", contextChunks: contexts.length, remainingQuestions: 0 };
  }
  const numbers = new Map();
  const lines = [res.decisionIntent ? "Here's what this project decided:" : "Here's what I found in this project:"];
  const citations = [];
  for (const p of chosen) {
    if (!numbers.has(p.ci)) numbers.set(p.ci, numbers.size + 1);
    const n = numbers.get(p.ci), h = contexts[p.ci];
    lines.push(`- ${p.sentence} [${n}]`);
    citations.push({ n, source: h.source, text: h.text, start: p.start, end: p.end, quote: p.sentence });
  }
  return { answer: lines.join("\n"), grounded: true, citations, model: "offline", contextChunks: contexts.length, remainingQuestions: 0 };
}

const MEMORIES = [
  { kind: "DECISION", title: "Let's go with Postgres instead of MongoDB because we need joins and real transactions for payments.",
    rationale: "we need joins and real transactions for payments", rejectedOptions: ["MongoDB"], conv: "Billing service: choosing a database", days: 33 },
  { kind: "DECISION", title: "We decided to use a Redis token bucket rather than an in-memory limiter, since we run three instances behind the load balancer.",
    rationale: "we run three instances behind the load balancer", rejectedOptions: ["an in-memory limiter"], conv: "Rate limiting the public API", days: 26 },
  { kind: "DECISION", title: "We'll use Stripe webhooks for payment status, since polling the Stripe API would hit their rate limits.",
    rationale: "polling the Stripe API would hit their rate limits", rejectedOptions: [], conv: "Billing service notes", days: 18 },
  { kind: "DECISION", title: "We chose server-side sessions instead of JWT because we need instant logout and only have one frontend.",
    rationale: "we need instant logout and only have one frontend", rejectedOptions: ["JWT"], conv: "Auth refactor: sessions vs JWT", days: 1 },
  { kind: "TASK", title: "add rate limit headers (X-RateLimit-Remaining) to every response", conv: "Rate limiting the public API", days: 26 },
  { kind: "TASK", title: "alert when a key hits the limit 100 times in an hour", conv: "Rate limiting the public API", days: 26 },
  { kind: "TASK", title: "Next step is writing the initial migration for customers, invoices and line_items.", conv: "Billing service: choosing a database", days: 33 },
  { kind: "TASK", title: "Still need to add CSRF protection to the session cookie.", conv: "Auth refactor: sessions vs JWT", days: 1 },
  { kind: "FIX", title: "Fix: Error: connect ECONNREFUSED 127.0.0.1:5432",
    body: "The issue is Postgres isn't running locally. Start it with `docker compose up -d` and check with `docker compose ps`.", conv: "Billing service: choosing a database", days: 33 },
  { kind: "FIX", title: "Fix: TypeError: Cannot read properties of undefined (reading 'user')",
    body: "The problem is the session is undefined on the first render, before the fetch resolves. Guard it with optional chaining: session?.user.", conv: "Auth refactor: sessions vs JWT", days: 1 },
];
const memories = new Map();   // projectId -> Memory[]
function memoriesFor(projectId) {
  if (!memories.has(projectId)) {
    memories.set(projectId, MEMORIES.map((m, i) => ({ id: randomUUID(), kind: m.kind, status: "ACTIVE", title: m.title, body: m.body ?? null,
      rationale: m.rationale ?? null, rejectedOptions: m.rejectedOptions ?? [], supersededBy: null, sourceMessageId: `msg-${i}`,
      conversationId: m.conv, conversationTitle: m.conv, createdAt: daysAgo(m.days) })));
  }
  return memories.get(projectId);
}
function findMemory(userId, id) {
  for (const [projectId, list] of memories) {
    const p = projects.find((x) => x.id === projectId);
    if (p && p.ownerId === userId) { const m = list.find((x) => x.id === id); if (m) return { m, list }; }
  }
  return null;
}


const tokens = new Map();     // userId -> TokenInfo[]
const dismissed = new Set();  // projectId:key

function driftFor(project) {
  const list = memoriesFor(project.id);
  const pg = list.find((m) => m.title.startsWith("Let's go with Postgres"));
  const sessions = list.find((m) => m.title.startsWith("We chose server-side sessions"));
  let jwt = list.find((m) => m.title.startsWith("We decided to use JWT"));
  if (!jwt) {
    jwt = { id: randomUUID(), kind: "DECISION", status: "ACTIVE", title: "We decided to use JWT for the analytics API tokens.",
      body: null, rationale: null, rejectedOptions: [], supersededBy: null, sourceMessageId: "msg-jwt",
      conversationId: "analytics-chat", conversationTitle: "analytics-chat", createdAt: daysAgo(0.2) };
    list.unshift(jwt);
  }
  const alerts = [];
  if (sessions && sessions.status === "ACTIVE" && jwt.status === "ACTIVE") {
    alerts.push({ key: "a1b2c3d4e5f6a7b8c9d0e1f2", type: "DECISION_CONFLICT", severity: "HIGH", decisionId: sessions.id,
      decisionTitle: sessions.title, decidedAt: sessions.createdAt, conflictingDecisionId: jwt.id, evidence: null,
      message: `This decision chooses JWT, which the ${new Date(sessions.createdAt).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" })} decision ruled out. Mark one of them as replaced.` });
  }
  if (pg && pg.status === "ACTIVE") {
    const sentence = "For raw events you could set up MongoDB: run npm install mongoose and connect to MongoDB Atlas with your connection string.";
    alerts.push({ key: "0f1e2d3c4b5a69788796a5b4", type: "USAGE_DRIFT", severity: "MEDIUM", decisionId: pg.id, decisionTitle: pg.title,
      decidedAt: pg.createdAt, conflictingDecisionId: null,
      evidence: { chunkId: "chunk-drift", conversationId: "analytics-chat", conversationTitle: "analytics-chat", sentAt: daysAgo(0.2),
        sentence, start: 13, end: 13 + sentence.length },
      message: `This conversation starts using MongoDB, but on ${new Date(pg.createdAt).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" })} the project chose PostgreSQL instead.` });
  }
  return alerts.filter((a) => !dismissed.has(project.id + ":" + a.key));
}

function contextPack(project, format, budget) {
  const list = memoriesFor(project.id);
  const day = (iso) => iso.slice(0, 10);
  const head = format === "agents" ? ["# AGENTS.md", "", `<!-- Generated by Notch on ${day(now())}. Edit decisions in Notch; this file is regenerated. -->`, "",
      `Project: ${project.name}. These decisions were made earlier in the project. Follow them unless the user says otherwise. If a request conflicts with one, point out the conflict before writing code.`]
    : format === "claude" ? ["# CLAUDE.md", "", `<!-- Generated by Notch on ${day(now())}. Edit decisions in Notch; this file is regenerated. -->`, "",
      `Project: ${project.name}. These decisions were made earlier in the project. Follow them unless the user says otherwise. If a request conflicts with one, point out the conflict before writing code.`]
    : [`# Context: ${project.name}`, "", "Here is where this project stands. Treat the decisions as settled unless I say otherwise."];
  const lines = [...head];
  const add = (l) => { if ((lines.join("\n").length + l.length) / 4 > budget) return false; lines.push(l); return true; };
  const decisions = list.filter((m) => m.kind === "DECISION" && m.status === "ACTIVE");
  let ok = add("") && add("## Decisions in force");
  for (const d of decisions) ok = ok && add(`- ${d.title} (decided ${day(d.createdAt)}${d.rejectedOptions.length ? "; rejected: " + d.rejectedOptions.join(", ") : ""})`);
  const tasks = list.filter((m) => m.kind === "TASK" && m.status === "ACTIVE");
  ok = ok && add("") && add("## Open tasks");
  for (const t of tasks) ok = ok && add(`- ${t.title}`);
  ok = ok && add("") && add("## Where we stopped") && add("- Last session: Auth refactor: sessions vs JWT (8 Nov)") && add("- You asked: Works now. Still need to add CSRF protection to the session cookie.");
  const fixes = list.filter((m) => m.kind === "FIX");
  ok = ok && add("") && add("## Known fixes");
  for (const f of fixes) ok = ok && add(`- ${f.title} → ${f.body}`);
  return lines.join("\n") + "\n";
}


// Ideation workbench: same rules as the Java ComparisonAnalyzer.
const comparisons = new Map();  // id -> { id, projectId, ownerId, status, comparison, decisionId, chosen, createdAt, updatedAt }
const DEFAULT_CRITERIA = [
  { id: "c1", name: "Fits the problem", weight: 5 }, { id: "c2", name: "Effort to build and run", weight: 3 },
  { id: "c3", name: "Team already knows it", weight: 3 }, { id: "c4", name: "Cost", weight: 2 }];

function seeded(str) {
  let h = 1779033703 ^ str.length;
  for (let i = 0; i < str.length; i++) { h = Math.imul(h ^ str.charCodeAt(i), 3432918353); h = (h << 13) | (h >>> 19); }
  return () => { h = Math.imul(h ^ (h >>> 16), 2246822507); h = Math.imul(h ^ (h >>> 13), 3266489909); h ^= h >>> 16; return (h >>> 0) / 4294967296; };
}

function validateComparison(c) {
  const p = [];
  if (!c.question?.trim()) p.push("Write the question you're deciding.");
  if (c.options.length < 2) p.push("Add at least two options to compare.");
  if (c.criteria.length === 0) p.push("Add at least one criterion to judge the options by.");
  const names = new Set();
  for (const o of c.options) {
    if (!o.name?.trim()) p.push("Every option needs a name.");
    else if (names.has(o.name.trim().toLowerCase())) p.push(`Two options are both called "${o.name.trim()}".`);
    else names.add(o.name.trim().toLowerCase());
  }
  const crit = new Set();
  for (const k of c.criteria) {
    if (!k.name?.trim()) p.push("Every criterion needs a name.");
    else if (crit.has(k.name.trim().toLowerCase())) p.push(`Two criteria are both called "${k.name.trim()}".`);
    else crit.add(k.name.trim().toLowerCase());
  }
  return [...new Set(p)];
}

function analyzeComparison(c) {
  const problems = validateComparison(c);
  let unscored = 0;
  for (const k of c.criteria) for (const o of c.options) if (c.scores?.[k.id]?.[o.id] == null) unscored++;
  if (problems.length) return { standings: [], winnerIds: [], robustness: [], samples: 0, decisive: [], unscored, problems };
  const totals = (w) => c.options.map((o) => c.criteria.reduce((sum, k, i) => sum + w[i] * (c.scores?.[k.id]?.[o.id] ?? 0), 0));
  const base = c.criteria.map((k) => k.weight);
  const t = totals(base), best = base.reduce((a, b) => a + b * 5, 0);
  const order = c.options.map((_, i) => i).sort((a, b) => t[b] - t[a] || c.options[a].name.localeCompare(c.options[b].name));
  let rank = 0, prev = NaN;
  const standings = order.map((i, pos) => { if (t[i] !== prev) rank = pos + 1; prev = t[i];
    return { optionId: c.options[i].id, name: c.options[i].name, percent: Math.round(1000 * t[i] / best) / 10, rank }; });
  const winnerIds = standings.filter((s) => s.rank === 1).map((s) => s.optionId);
  const rnd = seeded(JSON.stringify([c.options.map((o) => o.id), c.criteria, c.scores]));
  const wins = c.options.map(() => 0), N = 2000;
  for (let n = 0; n < N; n++) {
    const tt = totals(base.map((w) => w * (0.5 + rnd())));
    const top = Math.max(...tt), leaders = tt.filter((v) => v >= top - 1e-9).length;
    tt.forEach((v, i) => { if (v >= top - 1e-9) wins[i] += 1 / leaders; });
  }
  const robustness = c.options.map((o, i) => ({ optionId: o.id, name: o.name, share: Math.round(1000 * wins[i] / N) / 1000 }))
    .sort((a, b) => b.share - a.share || a.name.localeCompare(b.name));
  const lead = c.options[order[0]].id, next = c.options[order[1]].id;
  const decisive = c.criteria.map((k) => ({ criterionId: k.id, name: k.name,
    points: k.weight * ((c.scores?.[k.id]?.[lead] ?? 0) - (c.scores?.[k.id]?.[next] ?? 0)) }))
    .filter((d) => d.points > 0).sort((a, b) => b.points - a.points);
  return { standings, winnerIds, robustness, samples: N, decisive, unscored, problems: [] };
}

function comparisonView(x) {
  const { ownerId, ...rest } = x;
  return { ...rest, analysis: analyzeComparison(x.comparison) };
}

function seedComparisons(project, ownerId) {
  if ([...comparisons.values()].some((x) => x.projectId === project.id)) return;
  const id = randomUUID();
  comparisons.set(id, { id, projectId: project.id, ownerId, status: "OPEN", decisionId: null, chosen: null,
    createdAt: daysAgo(0.3), updatedAt: daysAgo(0.1), comparison: {
      question: "Where should raw analytics events live?",
      options: [{ id: "o1", name: "Postgres", note: "Already running for billing" }, { id: "o2", name: "Redis Streams", note: null },
        { id: "o3", name: "Kafka", note: null }],
      criteria: [{ id: "c1", name: "Fits append-only events", weight: 4 }, { id: "c2", name: "Effort to run", weight: 5 },
        { id: "c3", name: "Team already knows it", weight: 3 }, { id: "c4", name: "Cost", weight: 2 }],
      scores: { c1: { o1: 3, o2: 4, o3: 5 }, c2: { o1: 5, o2: 4, o3: 1 }, c3: { o1: 5, o2: 3, o3: 2 }, c4: { o1: 5, o2: 4, o3: 2 } } } });
}

function send(res, status, body) {
  res.writeHead(status, { "content-type": status >= 400 ? "application/problem+json" : "application/json" });
  res.end(body === undefined ? "" : JSON.stringify(body));
}
const problem = (res, status, code, detail) => send(res, status, { status, code, detail });

async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return Buffer.concat(chunks);
}

function login(handle) {
  const user = { id: randomUUID(), handle, avatarUrl: null, github: false };
  const token = "ntch_ses_" + randomUUID().replaceAll("-", "") + "mockmockmock";
  users.set(token, user);
  usage.set(user.id, { import: 17, question: 24 });
  seed(user);
  return { token, expiresAt: new Date(Date.now() + 30 * 86_400_000).toISOString(), user };
}

createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;
  const m = (re) => path.match(re);

  if (path === "/actuator/health") return send(res, 200, { status: "UP" });
  if (path === "/auth/config") return send(res, 200, { github: true, devLogin: true });
  if (path === "/auth/dev-login" && req.method === "POST") {
    const body = JSON.parse((await readBody(req)).toString() || "{}");
    return send(res, 200, login(body.handle || "dev"));
  }

  if (!path.startsWith("/api/")) return problem(res, 404, "not_found", "No such endpoint.");
  const token = (req.headers.authorization ?? "").replace(/^Bearer /, "");
  const user = users.get(token);
  if (!user) return problem(res, 401, "invalid_token", "Sign in again.");
  const own = (p) => p && p.ownerId === user.id;

  if (path === "/api/me") return send(res, 200, user);
  if (path === "/api/logout") { users.delete(token); return send(res, 204); }
  if (path === "/api/usage") {
    const u = usage.get(user.id);
    return send(res, 200, { remaining: { import: u.import, question: u.question }, limits: { import: 20, question: 30 } });
  }

  if (path === "/api/tokens" && req.method === "GET") return send(res, 200, tokens.get(user.id) ?? []);
  if (path === "/api/tokens" && req.method === "POST") {
    const body = JSON.parse((await readBody(req)).toString() || "{}");
    if (!body.name?.trim()) return problem(res, 400, "validation", "Name the token, for example after the device it's for.");
    const list = tokens.get(user.id) ?? [];
    if (list.length >= 10) return problem(res, 409, "too_many_tokens", "You have 10 tokens already. Revoke one you no longer use first.");
    const token = "ntch_pat_" + randomUUID().replaceAll("-", "") + "x4Kq9Zp2";
    const info = { id: randomUUID(), name: body.name.trim(), prefix: token.slice(0, 13), createdAt: now(), lastUsedAt: null };
    tokens.set(user.id, [info, ...list]);
    return send(res, 201, { ...info, token });
  }
  const tm = m(/^\/api\/tokens\/([^/]+)$/);
  if (tm && req.method === "DELETE") {
    const list = tokens.get(user.id) ?? [];
    if (!list.some((t) => t.id === tm[1])) return problem(res, 404, "not_found", "Token was not found.");
    tokens.set(user.id, list.filter((t) => t.id !== tm[1]));
    return send(res, 204);
  }
  if (path === "/api/projects" && req.method === "GET") {
    return send(res, 200, projects.filter(own).toReversed().map(({ ownerId, ...p }) => p));
  }
  if (path === "/api/projects" && req.method === "POST") {
    const body = JSON.parse((await readBody(req)).toString() || "{}");
    if (!body.name?.trim()) return problem(res, 400, "validation", "Give the project a name.");
    if (projects.some((p) => own(p) && p.name === body.name.trim())) {
      return problem(res, 409, "project_exists", `You already have a project called "${body.name.trim()}".`);
    }
    const p = { id: randomUUID(), ownerId: user.id, name: body.name.trim(), isPrivate: !!body.isPrivate, createdAt: now() };
    projects.push(p);
    const { ownerId, ...out } = p;
    return send(res, 201, out);
  }


  let mm = m(/^\/api\/memories\/([^/]+)(\/history|\/supersede)?$/);
  if (mm) {
    const found = findMemory(user.id, mm[1]);
    if (!found) return problem(res, 404, "not_found", "Memory was not found.");
    const { m: mem, list } = found;
    if (!mm[2] && req.method === "PATCH") {
      const body = JSON.parse((await readBody(req)).toString() || "{}");
      if (mem.kind === "DECISION") return problem(res, 400, "use_supersede", "To retire a decision, mark it as replaced by a newer one.");
      mem.status = String(body.status).toUpperCase() === "DONE" ? "DONE" : "ACTIVE";
      return send(res, 200, mem);
    }
    if (mm[2] === "/history") {
      const chain = [mem];
      let prev = list.find((x) => x.supersededBy === chain[0].id);
      while (prev) { chain.unshift(prev); prev = list.find((x) => x.supersededBy === prev.id); }
      let next = mem.supersededBy && list.find((x) => x.id === mem.supersededBy);
      while (next) { chain.push(next); next = next.supersededBy && list.find((x) => x.id === next.supersededBy); }
      return send(res, 200, chain);
    }
    if (mm[2] === "/supersede" && req.method === "POST") {
      const body = JSON.parse((await readBody(req)).toString() || "{}");
      if (mem.status !== "ACTIVE") return problem(res, 409, "already_replaced", "This decision has already been replaced.");
      let replacement = body.replacementId && list.find((x) => x.id === body.replacementId);
      if (!replacement) {
        if (!body.title?.trim()) return problem(res, 400, "missing_replacement", "Give a replacementId, or the title of the new decision.");
        replacement = { id: randomUUID(), kind: "DECISION", status: "ACTIVE", title: body.title.trim(), body: null, rationale: body.rationale || null,
          rejectedOptions: body.rejectedOptions ?? [], supersededBy: null, sourceMessageId: null, conversationId: null, conversationTitle: null, createdAt: now() };
        list.unshift(replacement);
      }
      mem.status = "SUPERSEDED"; mem.supersededBy = replacement.id;
      return send(res, 200, replacement);
    }
  }

  const cm = m(/^\/api\/comparisons\/([^/]+)(\/decide)?$/);
  if (cm) {
    const x = comparisons.get(cm[1]);
    if (!x || x.ownerId !== user.id) return problem(res, 404, "not_found", "Comparison was not found.");
    if (!cm[2] && req.method === "GET") return send(res, 200, comparisonView(x));
    if (!cm[2] && req.method === "DELETE") { comparisons.delete(x.id); return send(res, 204); }
    if (!cm[2] && req.method === "PUT") {
      if (x.status !== "OPEN") return problem(res, 409, "already_decided", "This comparison is decided. Start a new one to revisit the choice.");
      x.comparison = JSON.parse((await readBody(req)).toString()); x.updatedAt = now();
      return send(res, 200, comparisonView(x));
    }
    if (cm[2] && req.method === "POST") {
      const body = JSON.parse((await readBody(req)).toString() || "{}");
      if (x.status !== "OPEN") return problem(res, 409, "already_decided", "This comparison is already decided.");
      const problems = validateComparison(x.comparison);
      if (problems.length) return problem(res, 400, "invalid_comparison", problems[0]);
      const chosen = x.comparison.options.find((o) => o.id === body.optionId);
      if (!chosen) return problem(res, 400, "unknown_option", "Choose one of the options in this comparison.");
      const decision = { id: randomUUID(), kind: "DECISION", status: "ACTIVE",
        title: body.title?.trim() || `For \u201c${x.comparison.question}\u201d, we chose ${chosen.name}.`, body: "Decided in Compare.",
        rationale: body.rationale || null, rejectedOptions: x.comparison.options.filter((o) => o !== chosen).map((o) => o.name),
        supersededBy: null, sourceMessageId: null, conversationId: null, conversationTitle: null, createdAt: now() };
      memoriesFor(x.projectId).unshift(decision);
      x.status = "DECIDED"; x.decisionId = decision.id; x.chosen = chosen.name; x.updatedAt = now();
      return send(res, 200, { comparison: comparisonView(x), decision });
    }
  }
  let match = m(/^\/api\/projects\/([^/]+)(\/.*)?$/);
  if (!match) return problem(res, 404, "not_found", "No such endpoint.");
  const project = projects.find((p) => p.id === match[1]);
  if (!own(project)) return problem(res, 404, "not_found", "Project was not found.");
  const rest = match[2] ?? "";

  if (rest === "") { const { ownerId, ...out } = project; return send(res, 200, out); }



  if (rest === "/comparisons" && req.method === "GET") {
    seedComparisons(project, user.id);
    return send(res, 200, [...comparisons.values()].filter((x) => x.projectId === project.id)
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).map((x) => {
        const a = analyzeComparison(x.comparison);
        return { id: x.id, question: x.comparison.question, status: x.status, options: x.comparison.options.map((o) => o.name),
          leader: a.winnerIds.length === 1 ? a.standings[0].name : null, chosen: x.chosen, updatedAt: x.updatedAt };
      }));
  }
  if (rest === "/comparisons" && req.method === "POST") {
    const body = JSON.parse((await readBody(req)).toString() || "{}");
    if (!body.question?.trim()) return problem(res, 400, "validation", "Write the question you're deciding.");
    const id = randomUUID();
    const options = (body.options ?? []).filter((n) => n?.trim()).map((n, i) => ({ id: `o${i + 1}`, name: n.trim(), note: null }));
    comparisons.set(id, { id, projectId: project.id, ownerId: user.id, status: "OPEN", decisionId: null, chosen: null,
      createdAt: now(), updatedAt: now(), comparison: { question: body.question.trim(), options, criteria: DEFAULT_CRITERIA, scores: {} } });
    return send(res, 201, comparisonView(comparisons.get(id)));
  }
  if (rest === "/drift") return send(res, 200, driftFor(project));
  const dm = rest.match(/^\/drift\/([0-9a-f]{24})\/dismiss$/);
  if (dm && req.method === "POST") { dismissed.add(project.id + ":" + dm[1]); return send(res, 204); }
  if (rest === "/resume") {
    const list = memoriesFor(project.id);
    return send(res, 200, {
      lastSession: { conversationId: "auth", title: "Auth refactor: sessions vs JWT", lastActivity: daysAgo(1), messages: 6 },
      whereYouStopped: ["Last session: Auth refactor: sessions vs JWT (8 Nov)",
        "You asked: Works now. Still need to add CSRF protection to the session cookie.",
        "The reply began: The problem is the session is undefined on the first render, before the fetch resolves. Guard it with optional chaining: session?.user, and render a loading state until the session request finishes."],
      openTasks: list.filter((x) => x.kind === "TASK" && x.status === "ACTIVE").map((t, i) => ({ id: t.id, title: t.title, createdAt: t.createdAt, priority: 0.9 - i * 0.2 })),
      decisions: list.filter((x) => x.kind === "DECISION" && x.status === "ACTIVE"),
      fixes: list.filter((x) => x.kind === "FIX"),
      driftAlerts: driftFor(project).length,
    });
  }
  if (rest === "/context-pack") {
    const budget = Math.max(200, Math.min(8000, Number(url.searchParams.get("budget") ?? 1500)));
    const markdown = contextPack(project, "pack", budget);
    return send(res, 200, { markdown, estimatedTokens: Math.ceil(markdown.length / 4), budget });
  }
  const em = rest.match(/^\/export\/(agents|claude)-md$/);
  if (em) {
    res.writeHead(200, { "content-type": "text/markdown; charset=utf-8",
      "content-disposition": `attachment; filename="${em[1] === "agents" ? "AGENTS.md" : "CLAUDE.md"}"` });
    return res.end(contextPack(project, em[1], 3000));
  }
  if (rest === "/index") {
    return send(res, 200, { version: 4, stats: { chunks: CHUNKS.length + 14, conversations: 5, indexedChars: 6810, autocompleteKeys: 37,
      threads: 4, buildMillis: 6, builtAt: daysAgo(0.05) } });
  }
  if (rest === "/threads") {
    return send(res, 200, [{ threadId: "t1", conversations: [{ id: "c1", title: "Billing service: choosing a database" }, { id: "c2", title: "pasted-billing-chat" }] }]);
  }
  if (rest === "/search") return send(res, 200, search(url.searchParams.get("q") ?? ""));
  if (rest === "/autocomplete") {
    const prefix = (url.searchParams.get("prefix") ?? "").toLowerCase();
    return send(res, 200, KEYS.filter((k) => k.toLowerCase().startsWith(prefix)).slice(0, 8).map((key, i) => ({ key, weight: 10 - i })));
  }
  if (rest === "/ask" && req.method === "POST") {
    const body = JSON.parse((await readBody(req)).toString() || "{}");
    const u = usage.get(user.id);
    const out = ask(body.question ?? "");
    if (out.grounded) u.question = Math.max(0, u.question - 1);
    out.remainingQuestions = u.question;
    await new Promise((r) => setTimeout(r, 700));
    return send(res, 200, out);
  }
  if (rest === "/memories" && req.method === "GET") {
    const kind = url.searchParams.get("kind")?.toUpperCase(), status = url.searchParams.get("status")?.toUpperCase();
    return send(res, 200, memoriesFor(project.id).filter((x) => (!kind || x.kind === kind) && (!status || x.status === status))
      .toSorted((a, b) => b.createdAt.localeCompare(a.createdAt)));
  }
  if (rest === "/memories" && req.method === "POST") {
    const body = JSON.parse((await readBody(req)).toString() || "{}");
    if (!body.title?.trim()) return problem(res, 400, "validation", "Write the decision or task first.");
    const mem = { id: randomUUID(), kind: String(body.kind).toUpperCase(), status: "ACTIVE", title: body.title.trim(), body: body.body || null,
      rationale: body.rationale || null, rejectedOptions: body.rejectedOptions ?? [], supersededBy: null, sourceMessageId: null,
      conversationId: null, conversationTitle: null, createdAt: now() };
    memoriesFor(project.id).unshift(mem);
    return send(res, 201, mem);
  }
  if (rest === "/conversations") {
    return send(res, 200, conversations.filter((c) => c.projectId === project.id).map(({ projectId, ...c }) => c));
  }
  if (rest === "/imports" && req.method === "GET") {
    return send(res, 200, imports.filter((i) => i.projectId === project.id).toReversed().map(({ ownerId, ...i }) => i));
  }
  if (rest === "/imports" && req.method === "POST") {
    const raw = (await readBody(req)).toString("latin1");
    const filename = raw.match(/filename="([^"]+)"/)?.[1] ?? "upload";
    const u = usage.get(user.id);
    if (u.import <= 0) return problem(res, 429, "daily_limit_reached", "You've used today's 20 imports.");
    u.import -= 1;
    const r = { id: randomUUID(), projectId: project.id, ownerId: user.id, filename,
      format: filename.endsWith(".md") ? "markdown" : filename.endsWith(".txt") ? "text" : "chatgpt",
      status: "PROCESSING", conversationsAdded: 1, conversationsUpdated: 0, messagesAdded: 5, messagesSkipped: 2,
      chunksAdded: 4, duplicateChunks: 2, bloomFalsePositives: 1, secretsRedacted: 1, memoriesExtracted: 0,
      error: null, createdAt: now(), finishedAt: null };
    imports.push(r);
    conversations.push({ id: randomUUID(), projectId: project.id, source: r.format, title: filename.replace(/\.[^.]+$/, ""), startedAt: now(), messageCount: 5 });
    setTimeout(() => { r.status = "READY"; r.memoriesExtracted = 3; r.finishedAt = now(); }, 3000);
    const { ownerId, ...out } = r;
    return send(res, 202, out);
  }
  match = rest.match(/^\/imports\/([^/]+)$/);
  if (match) {
    const r = imports.find((i) => i.id === match[1] && i.projectId === project.id);
    if (!r) return problem(res, 404, "not_found", "Import was not found.");
    const { ownerId, ...out } = r;
    return send(res, 200, out);
  }
  return problem(res, 404, "not_found", "Not in the mock API yet.");
}).listen(PORT, () => console.log(`Mock Notch API on http://localhost:${PORT}`));
