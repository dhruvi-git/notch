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

## Configuration

| Variable | Default (local profile) | Notes |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/notch` | JDBC form; for Neon add `?sslmode=require` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | `notch` / `notch` | |
| `NOTCH_CRYPTO_KEY` | development key | Required in `cloud`. `openssl rand -base64 32` |
| `NOTCH_CORS_ORIGINS` | `http://localhost:3000` | Comma-separated |
| `SPRING_PROFILES_ACTIVE` | `local` | `cloud` rejects all requests until sign-in exists (phase 2f) |

## Current limits

- The language model and embeddings are offline stand-ins (`HeuristicMemoryExtractor`,
  `HashingEmbeddingClient`). Real providers plug in through `LlmConfig`.
- Conversation titles are secret-scanned but stored in plain text; message bodies and memory
  payloads are encrypted.
- Only the local profile has a user. GitHub sign-in arrives in phase 2f.
