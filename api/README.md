# Notch API

Spring Boot 4.1 REST API around the algorithm engine. It handles storage, validation,
background jobs and HTTP; every assessed algorithm runs in `notch-engine`.

## Run it locally

Prerequisites: JDK 21, Maven 3.9+, Docker Desktop.

```bash
docker compose up -d                 # Postgres 17 with pgvector, from the repo root
mvn -q install -DskipTests           # builds engine and api
mvn -pl api spring-boot:run          # starts on http://localhost:8080 with the "local" profile
```

The `local` profile uses a seeded developer account and a development encryption key, so no
environment variables are needed. Check it is up: `curl localhost:8080/actuator/health`.

## Walkthrough with the sample data

```bash
# 1. Create a project
curl -s -X POST localhost:8080/api/projects \
  -H 'Content-Type: application/json' -d '{"name":"Billing service"}'
# copy the "id" from the response
P=<project-id>

# 2. Import a ChatGPT export (a zip or conversations.json both work)
curl -s -F file=@samples/chatgpt-conversations.json localhost:8080/api/projects/$P/imports
#    The report shows messagesAdded, chunksAdded and secretsRedacted: 1 (the fake API key).

# 3. Import a pasted copy of part of the same chat
curl -s -F file=@samples/pasted-billing-chat.txt localhost:8080/api/projects/$P/imports
#    duplicateChunks: 3. The Bloom filter flagged them, the exact check confirmed them,
#    and they are not stored or embedded again.

# 4. Import the same export again
curl -s -F file=@samples/chatgpt-conversations.json localhost:8080/api/projects/$P/imports
#    messagesSkipped: 11, messagesAdded: 0, status READY immediately.

# 5. Import a Claude export and a markdown note
curl -s -F file=@samples/claude-conversations.json localhost:8080/api/projects/$P/imports
curl -s -F file=@samples/billing-notes.md localhost:8080/api/projects/$P/imports

# 6. After a few seconds (background job), read the decision ledger
curl -s "localhost:8080/api/projects/$P/memories?kind=decision"
curl -s "localhost:8080/api/projects/$P/memories?kind=task"
curl -s "localhost:8080/api/projects/$P/memories?kind=fix"

# 7. Search: exact error codes, stems ("caching" finds "cache") and "why this result" scores
curl -s "localhost:8080/api/projects/$P/search?q=ECONNREFUSED"
curl -s "localhost:8080/api/projects/$P/search?q=how+do+we+rate+limit"

# 8. Ask for a cited answer (uses one of 30 daily questions)
curl -s -X POST localhost:8080/api/projects/$P/ask \
  -H 'Content-Type: application/json' -d '{"question":"What did we decide about the database?"}'
#    -> "Let's go with Postgres instead of MongoDB because we need joins ... [1]"
#       with citation 1 pointing at the exact sentence in the ChatGPT conversation.

# 9. Autocomplete, threads and index statistics
curl -s "localhost:8080/api/projects/$P/autocomplete?prefix=econ"
curl -s localhost:8080/api/projects/$P/threads
curl -s localhost:8080/api/projects/$P/index

# 10. Import a later chat that drifts from earlier decisions
curl -s -F file=@samples/analytics-chat.txt localhost:8080/api/projects/$P/imports
#    After the background job, check for drift:
curl -s localhost:8080/api/projects/$P/drift
#    -> HIGH: "This decision chooses JWT, which the ... decision ruled out."
#    -> MEDIUM: "This conversation starts using MongoDB, but on ... the project chose PostgreSQL instead."

# 11. Resolve the JWT conflict: replace the sessions decision with the new one
curl -s "localhost:8080/api/projects/$P/memories?kind=decision"     # find both ids
curl -s -X POST localhost:8080/api/memories/<sessions-decision-id>/supersede \
  -H 'Content-Type: application/json' -d '{"replacementId":"<jwt-decision-id>"}'
curl -s localhost:8080/api/memories/<jwt-decision-id>/history     # oldest first

# 12. Dismiss an alert you've decided is fine
curl -s -X POST localhost:8080/api/projects/$P/drift/<alert-key>/dismiss

# 13. Where you stopped, a context pack, and agent files
curl -s localhost:8080/api/projects/$P/resume
curl -s "localhost:8080/api/projects/$P/context-pack?budget=1500"
curl -s -OJ localhost:8080/api/projects/$P/export/agents-md     # saves AGENTS.md
curl -s -OJ localhost:8080/api/projects/$P/export/claude-md     # saves CLAUDE.md


# Other endpoints
curl -s localhost:8080/api/projects/$P/imports
curl -s localhost:8080/api/projects/$P/conversations
curl -s localhost:8080/api/usage
```

## Endpoints so far

| Method | Path | Does |
|---|---|---|
| POST | `/api/projects` | Create a project (`{"name": "...", "isPrivate": false}`) |
| GET | `/api/projects`, `/api/projects/{id}` | List or read projects |
| POST | `/api/projects/{id}/imports` | Upload `file` (multipart); optional `format`: chatgpt, claude, markdown, text |
| GET | `/api/projects/{id}/imports[/{importId}]` | Import reports and status |
| GET | `/api/projects/{id}/conversations` | Imported conversations with message counts |
| GET | `/api/projects/{id}/memories` | Memories; filters `kind`, `status`, `limit` |
| GET | `/api/projects/{id}/search?q=&k=&since=` | Hybrid search with highlights and per-signal scores (`why`) |
| POST | `/api/projects/{id}/ask` | Cited answer (`{"question": "..."}`); uses the daily allowance |
| GET | `/api/projects/{id}/autocomplete?prefix=&k=` | `@` suggestions from the radix tree |
| GET | `/api/projects/{id}/threads` | Conversations linked by shared files or errors (union-find) |
| GET | `/api/projects/{id}/index` | Index size, build time and version |
| POST | `/api/projects/{id}/memories` | Add a decision, task or context by hand |
| PATCH | `/api/memories/{memoryId}` | Mark a task `done` or `active` |
| POST | `/api/memories/{memoryId}/supersede` | Replace a decision (`replacementId`, or `title` for a new one) |
| GET | `/api/memories/{memoryId}/history` | A decision's history, oldest first |
| GET | `/api/projects/{id}/drift` | Drift alerts against active decisions |
| POST | `/api/projects/{id}/drift/{alertKey}/dismiss` | Hide an alert |
| GET | `/api/projects/{id}/resume` | Where you stopped: last session, top tasks, decisions, fixes |
| GET | `/api/projects/{id}/context-pack?budget=` | Markdown briefing for a new chat, within a token budget |
| GET | `/api/projects/{id}/export/agents-md`, `/export/claude-md` | Agent instruction files as downloads |
| GET | `/api/usage` | Remaining daily allowance |

Errors use the RFC 9457 problem format with a stable `code`, for example:

```json
{ "status": 429, "title": "Too Many Requests", "code": "daily_limit_reached",
  "detail": "You've used today's 20 imports. Search still works, and the allowance resets at midnight UTC." }
```

## How an import works

1. **Read** the file: zip archives are unpacked (with a 64 MB unzipped cap against zip bombs),
   JSON is tried as a ChatGPT then a Claude export, and anything else is read as a transcript
   or markdown note.
2. **Redact** secrets with the engine's KMP-based `SecretScanner`.
3. **Store** new messages, encrypted with AES-256-GCM and bound to their owner. Messages
   already imported (same export id) are skipped.
4. **Chunk** each message (paragraph-aware, up to 1,200 characters).
5. **Deduplicate** each chunk: the project's Bloom filter answers "definitely new" without a
   query; "possibly seen" is confirmed with an exact lookup, and unconfirmed hits are reported
   as `bloomFalsePositives`.
6. **Queue** a background job, which embeds new chunks and extracts decisions, tasks and fixes.

Steps 1–5 run in one transaction, so a failed import leaves nothing behind and does not use
up the daily allowance. Private projects are never embedded, and their memories always come
from the offline extractor.

## How search and answers work

Each project has an in-memory index built from its decrypted chunks: a suffix array for exact
matching, a radix tree of code-shaped keys for autocomplete, and a union-find over shared
files and errors for threads. The first search builds it; after each import the background
job rebuilds it while searches keep using the old one, then swaps it in.

A query is parsed into exact terms (stems for ordinary words, identifiers kept whole), quoted
phrases, soft expansions ("database" also looks for "postgres"), a decision flag ("what did
we decide") and a time window ("last week"). Exact and semantic (pgvector) candidates are
merged and ranked by the engine's `HybridRanker`. `ask` keeps the best chunks that fit a
3,000-token budget, asks the model to answer only from them, and drops any citation that does
not point at a supplied chunk. The offline model answers by quoting source sentences, so every
line it returns is grounded; decision questions are answered only with decision sentences.
Questions with no matching sources do not use up the allowance.

## How the decision features work

**Capture.** Decisions extracted from imports (or added by hand) form a ledger. Replacing a
decision marks it `SUPERSEDED` and links it to its replacement, so `history` shows how the
plan changed. Replacement is checked: same project, an active decision, and no loops.

**Guard.** `TechCatalog` maps tool names and aliases ("mongo", "mongoose") to one tool and a
category; `DecisionFacts` reads what a decision chose and rejected. Drift detection then
compares newer decisions with older ones (choosing a rejected tool, dropping a chosen one, or
picking a different tool of the same kind), and searches later conversations for rejected
tools with the suffix array. A sentence counts as drift only when it starts using the tool
("npm install mongoose") rather than dismissing it ("instead of MongoDB"). Drift inside the
same thread as the decision is rated HIGH. For non-private projects the language model gets a
final say on each candidate. Alerts are computed on demand; only dismissals are stored.

**Sync.** The context pack and the `AGENTS.md` / `CLAUDE.md` exports are written by
`ContextPackBuilder`: decisions first (newest first, with what they rejected), then the top
open tasks (ranked by randomized selection on recency and last-session relevance), where you
stopped, and known fixes. Sections are added strictly in that order until the token budget
runs out; agent files are also capped at 150 lines and tell the agent to raise conflicts
before writing code.

## Configuration

| Variable | Default (local profile) | Notes |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/notch` | JDBC form; for Neon add `?sslmode=require` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | `notch` / `notch` | |
| `NOTCH_CRYPTO_KEY` | development key | Required in `cloud`. `openssl rand -base64 32` |
| `NOTCH_CORS_ORIGINS` | `http://localhost:3000` | Comma-separated |
| `SPRING_PROFILES_ACTIVE` | `local` | `cloud` rejects all requests until sign-in exists (phase 2f) |

## Current limits

- The language model and embeddings are offline stand-ins (`OfflineLlmClient`,
  `HashingEmbeddingClient`). Real providers plug in through `LlmConfig`. Offline semantic
  search only matches shared words, which is why the query expansion exists.
- Indexes live in each API instance's memory. With several instances, each builds its own;
  that is fine at this scale.
- Conversation titles are secret-scanned but stored in plain text; message bodies and memory
  payloads are encrypted.
- Only the local profile has a user. GitHub sign-in arrives in phase 2f.
