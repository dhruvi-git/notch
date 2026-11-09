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

  let match = m(/^\/api\/projects\/([^/]+)(\/.*)?$/);
  if (!match) return problem(res, 404, "not_found", "No such endpoint.");
  const project = projects.find((p) => p.id === match[1]);
  if (!own(project)) return problem(res, 404, "not_found", "Project was not found.");
  const rest = match[2] ?? "";

  if (rest === "") { const { ownerId, ...out } = project; return send(res, 200, out); }
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
