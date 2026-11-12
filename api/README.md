# Notch API

Spring Boot 4.1 REST API around the algorithm engine. It handles storage, validation,
background jobs and HTTP; every assessed algorithm runs in `notch-engine`.

## Run it locally

Prerequisites: JDK 21, Maven 3.9+, Docker Desktop.

```bash
docker compose up -d                 # Postgres 17 with pgvector, from the repo root
mvn -q install -DskipTests           # builds engine and api
mvn -pl api spring-boot:run          # starts on http://localhost:8090 with the "local" profile
```

The `local` profile needs no environment variables: it uses a development encryption key, and
requests without a token act as a seeded developer account, so the `curl` commands below work
as written. Check it is up: `curl localhost:8090/actuator/health`.

## Walkthrough with the sample data

```bash
# 1. Create a project
curl -s -X POST localhost:8090/api/projects \
  -H 'Content-Type: application/json' -d '{"name":"Billing service"}'
# copy the "id" from the response
P=<project-id>

# 2. Import a ChatGPT export (a zip or conversations.json both work)
curl -s -F file=@samples/chatgpt-conversations.json localhost:8090/api/projects/$P/imports
#    The report shows messagesAdded, chunksAdded and secretsRedacted: 1 (the fake API key).

# 3. Import a pasted copy of part of the same chat
curl -s -F file=@samples/pasted-billing-chat.txt localhost:8090/api/projects/$P/imports
#    duplicateChunks: 3. The Bloom filter flagged them, the exact check confirmed them,
#    and they are not stored or embedded again.

# 4. Import the same export again
curl -s -F file=@samples/chatgpt-conversations.json localhost:8090/api/projects/$P/imports
#    messagesSkipped: 11, messagesAdded: 0, status READY immediately.

# 5. Import a Claude export and a markdown note
curl -s -F file=@samples/claude-conversations.json localhost:8090/api/projects/$P/imports
curl -s -F file=@samples/billing-notes.md localhost:8090/api/projects/$P/imports

# 6. After a few seconds (background job), read the decision ledger
curl -s "localhost:8090/api/projects/$P/memories?kind=decision"
curl -s "localhost:8090/api/projects/$P/memories?kind=task"
curl -s "localhost:8090/api/projects/$P/memories?kind=fix"

# 7. Search: exact error codes, stems ("caching" finds "cache") and "why this result" scores
curl -s "localhost:8090/api/projects/$P/search?q=ECONNREFUSED"
curl -s "localhost:8090/api/projects/$P/search?q=how+do+we+rate+limit"

# 8. Ask for a cited answer (uses one of 30 daily questions)
curl -s -X POST localhost:8090/api/projects/$P/ask \
  -H 'Content-Type: application/json' -d '{"question":"What did we decide about the database?"}'
#    -> "Let's go with Postgres instead of MongoDB because we need joins ... [1]"
#       with citation 1 pointing at the exact sentence in the ChatGPT conversation.

# 9. Autocomplete, threads and index statistics
curl -s "localhost:8090/api/projects/$P/autocomplete?prefix=econ"
curl -s localhost:8090/api/projects/$P/threads
curl -s localhost:8090/api/projects/$P/index

# 10. Import a later chat that drifts from earlier decisions
curl -s -F file=@samples/analytics-chat.txt localhost:8090/api/projects/$P/imports
#    After the background job, check for drift:
curl -s localhost:8090/api/projects/$P/drift
#    -> HIGH: "This decision chooses JWT, which the ... decision ruled out."
#    -> MEDIUM: "This conversation starts using MongoDB, but on ... the project chose PostgreSQL instead."

# 11. Resolve the JWT conflict: replace the sessions decision with the new one
curl -s "localhost:8090/api/projects/$P/memories?kind=decision"     # find both ids
curl -s -X POST localhost:8090/api/memories/<sessions-decision-id>/supersede \
  -H 'Content-Type: application/json' -d '{"replacementId":"<jwt-decision-id>"}'
curl -s localhost:8090/api/memories/<jwt-decision-id>/history     # oldest first

# 12. Dismiss an alert you've decided is fine
curl -s -X POST localhost:8090/api/projects/$P/drift/<alert-key>/dismiss

# 13. Where you stopped, a context pack, and agent files
curl -s localhost:8090/api/projects/$P/resume
curl -s "localhost:8090/api/projects/$P/context-pack?budget=1500"
curl -s -OJ localhost:8090/api/projects/$P/export/agents-md     # saves AGENTS.md
curl -s -OJ localhost:8090/api/projects/$P/export/claude-md     # saves CLAUDE.md


# Other endpoints
curl -s localhost:8090/api/projects/$P/imports
curl -s localhost:8090/api/projects/$P/conversations
curl -s localhost:8090/api/usage
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
| POST | `/api/projects/{id}/code-maps` | Upload a map from `notch map` (`{"label", "map"}`) |
| POST | `/api/projects/{id}/code-maps/zip` | Upload a zipped codebase (25 MB); it's mapped and the code is discarded |
| GET | `/api/projects/{id}/code-map` | Overview: stats, modules, areas, start here, entry points, possibly unused, conflicts, tools |
| GET | `/api/projects/{id}/code-map/files` | Every file with its role and summary |
| GET | `/api/projects/{id}/code-map/file?path=` | One file: what, how (imports, used by, affected) and why (decisions, chats) |
| GET, POST | `/api/projects/{id}/comparisons` | List or start comparisons (`{"question", "options": [...]}`) |
| GET, PUT, DELETE | `/api/comparisons/{id}` | Read, autosave (returns a fresh analysis) or delete a comparison |
| POST | `/api/comparisons/{id}/decide` | Record the chosen option as a decision; the others become "ruled out" |
| POST | `/api/projects/{id}/memories` | Add a decision, task or context by hand |
| PATCH | `/api/memories/{memoryId}` | Mark a task `done` or `active` |
| POST | `/api/memories/{memoryId}/supersede` | Replace a decision (`replacementId`, or `title` for a new one) |
| GET | `/api/memories/{memoryId}/history` | A decision's history, oldest first |
| GET | `/api/projects/{id}/drift` | Drift alerts against active decisions |
| POST | `/api/projects/{id}/drift/{alertKey}/dismiss` | Hide an alert |
| GET | `/api/projects/{id}/resume` | Where you stopped: last session, top tasks, decisions, fixes |
| GET | `/api/projects/{id}/context-pack?budget=` | Markdown briefing for a new chat, within a token budget |
| GET | `/api/projects/{id}/export/agents-md`, `/export/claude-md` | Agent instruction files as downloads |
| GET | `/auth/config` | Which sign-in options this server offers (public) |
| GET | `/auth/github/start?redirect=` | Starts GitHub sign-in (public) |
| GET | `/auth/github/callback` | GitHub returns here; redirects to the web app with a one-time code (public) |
| POST | `/auth/exchange` | Trades the one-time code for a session token (public) |
| POST | `/auth/dev-login` | Local profile only: a session token for a test user, `{"handle": "alice"}` |
| POST | `/api/logout` | Revokes the current session token |
| GET | `/api/me` | The signed-in user |
| GET, POST | `/api/tokens` | List or create personal tokens for the extensions (shown once) |
| DELETE | `/api/tokens/{tokenId}` | Revoke a personal token |
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

## Sign-in and tokens

Every `/api/**` request carries `Authorization: Bearer <token>`. The web app and the API are on
different domains, so the API issues its own tokens instead of relying on cookies, which
browsers increasingly block across sites.

1. The web app sends the browser to `/auth/github/start?redirect=<web callback>`. The redirect
   must be one of the allowed origins (no open redirects).
2. The API redirects to GitHub with a signed, expiring `state` (HMAC-SHA256), asking only for
   `read:user`.
3. GitHub returns to `/auth/github/callback`. The API verifies the state, reads the GitHub
   user, creates or updates the account, and redirects to the web app with a **one-time code**
   (valid 60 seconds, usable once).
4. The web app posts the code to `/auth/exchange` and gets a 30-day session token. The token
   itself never appears in a URL, so it can't leak through history or server logs.

Personal tokens (`ntch_pat_…`) for the Chrome and VS Code extensions are created in the app,
shown once, and can be revoked. Only SHA-256 hashes of tokens are stored. `AuthFilter`
rejects missing, malformed, expired and revoked tokens with a 401 before any controller runs.

**Setting up GitHub sign-in:** GitHub → Settings → Developer settings → OAuth Apps → New OAuth
App. Set the homepage to the web app's URL and the authorization callback URL to
`<api-url>/auth/github/callback`, then put the client id and secret in `GITHUB_CLIENT_ID`
and `GITHUB_CLIENT_SECRET`. Locally, sign-in also works without GitHub through
`/auth/dev-login`.

## Live tests

Two test classes run against a live API and are skipped otherwise:
`WalkthroughSmokeTest` runs this README's walkthrough as 13 checked steps, and
`CrossUserIsolationTest` is described below. See [`../RUNNING.md`](../RUNNING.md) for the
commands.

## Proving users can't see each other's data

`CrossUserIsolationTest` runs against a live API. Two test users are created; Alice makes a
project, imports a file, records a decision and creates a token. Bob then tries every endpoint
against Alice's ids (reading, searching, asking, importing, editing memories, revoking her
token) and must get 404 every time: never 403, which would confirm the id exists. It also
checks that malformed, unknown and revoked tokens get 401.

```bash
mvn -pl api spring-boot:run                                          # terminal 1
NOTCH_API_URL=http://localhost:8090 mvn -pl api test -Dtest=CrossUserIsolationTest   # terminal 2
```

Without `NOTCH_API_URL` the test is skipped, so `mvn test` never needs a running server.

## Search evaluation

`SearchEvaluation` scores search on 50 labelled questions over a fictional startup's chats
(`src/test/resources/eval/`), comparing hybrid ranking with exact-only and vector-only. Every
chat goes through the real import steps and the app's ranking code (`SearchPipeline`). Results
are in [`../docs/evaluation.md`](../docs/evaluation.md); regenerate them with:

```bash
mvn -q -pl api test -Dtest=SearchEvaluationTest -Dnotch.eval.out=../docs/evaluation.md
```

The test also fails the build if hybrid ranking ever scores below either signal alone.

## How Explain works

The `explain` module builds a map from source files: language, role (page, API route,
controller, component, test, config...), a summary from the file's own doc comment or from
what it is, exports, endpoints (Next.js routes, Spring, Express, FastAPI, Flask), imports
resolved to files (relative and `@/` paths, Python packages, Java classes, Go modules), and
outside packages matched to known tools. Java same-package uses are found with KMP; areas
with union-find; the ten files to read first with randomized selection; unused files with a
breadth-first walk from everything a framework or person runs directly.

Maps come from `notch map` (built locally; only the map is sent) or a zip (mapped here, code
discarded). They're stored encrypted, five per project. The **why** is joined in when a file
is viewed: decisions whose chosen or ruled-out tools the file imports (a file importing a
ruled-out tool is a conflict), and conversation passages that mention the file's path, its
name, or one of its folders (folders are looked up in the radix tree of keys first, so only
mentioned folders are searched).

## How Compare works

`ComparisonAnalyzer` scores a decision matrix: each option's weighted sum as a share of the
best possible, ranks with shared places for ties, and the criteria that create the leader's
lead over the runner-up. Because weights are opinions, it also runs a **Monte Carlo
robustness check**: 2,000 re-scorings with each weight multiplied by an independent random
factor in [0.5, 1.5], reporting the share of runs each option wins (ties split). The generator
is seeded from the matrix, so the same matrix always shows the same numbers. Cost is
O(samples × criteria × options). Matrices are stored encrypted, like memories. Deciding writes
a decision whose rejected options are the other choices, which is what the guard checks code
against.

## Configuration

| Variable | Default (local profile) | Notes |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5433/notch` | JDBC form; for Neon add `?sslmode=require` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | `notch` / `notch` | |
| `NOTCH_CRYPTO_KEY` | development key | Required in `cloud`. `openssl rand -base64 32` |
| `NOTCH_CORS_ORIGINS` | `http://localhost:3000` | Comma-separated |
| `NOTCH_AUTH_SECRET` | development secret | Required in `cloud`; 32+ characters, signs the OAuth state |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | unset | GitHub sign-in is disabled until both are set |
| `GITHUB_CALLBACK_URL` | `http://localhost:8090/auth/github/callback` | Must match the OAuth app |
| `SPRING_PROFILES_ACTIVE` | `local` | `cloud` requires a token on every `/api` request |

## Current limits

- The language model and embeddings are offline stand-ins (`OfflineLlmClient`,
  `HashingEmbeddingClient`). Real providers plug in through `LlmConfig`. Offline semantic
  search only matches shared words, which is why the query expansion exists.
- Indexes live in each API instance's memory. With several instances, each builds its own;
  that is fine at this scale.
- Conversation titles are secret-scanned but stored in plain text; message bodies and memory
  payloads are encrypted.
- Authentication is a small explicit servlet filter rather than Spring Security; it covers
  bearer tokens only, which is all this API needs.
