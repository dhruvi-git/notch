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
  let match = m(/^\/api\/projects\/([^/]+)(\/.*)?$/);
  if (!match) return problem(res, 404, "not_found", "No such endpoint.");
  const project = projects.find((p) => p.id === match[1]);
  if (!own(project)) return problem(res, 404, "not_found", "Project was not found.");
  const rest = match[2] ?? "";

  if (rest === "") { const { ownerId, ...out } = project; return send(res, 200, out); }

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
