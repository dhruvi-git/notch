# Running Notch locally for the first time

About 15 minutes. Run every command from the repository root (the folder with `pom.xml`).

## 1. Check the prerequisites

```bash
java -version      # must say 21 (or newer)
mvn -version       # 3.9 or newer, and "Java version: 21" in its output
docker --version   # Docker Desktop must also be running
```

If `mvn -version` shows an older Java than `java -version`, set `JAVA_HOME` to your JDK 21.

## 2. Start the database

```bash
docker compose up -d
docker compose ps          # "db" should show "healthy" after a few seconds
```

## 3. Build and run the tests

```bash
mvn -q install
```

This compiles both modules and runs the engine and API unit tests (about 200). The live tests
below are skipped here because no server is running yet.

## 4. Start the API

```bash
mvn -pl api spring-boot:run
```

Wait for `Started NotchApiApplication`. On the first start Flyway creates the tables
(`Successfully applied 3 migrations`). Leave this terminal open.

Quick check in a second terminal:

```bash
curl localhost:8090/actuator/health          # {"status":"UP"}
curl localhost:8090/auth/config              # {"github":false,"devLogin":true}
```

## 5. Run the live tests

In the second terminal:

macOS / Linux:
```bash
NOTCH_API_URL=http://localhost:8090 mvn -pl api test -Dtest='WalkthroughSmokeTest,CrossUserIsolationTest'
```

Windows (PowerShell):
```powershell
$env:NOTCH_API_URL="http://localhost:8090"; mvn -pl api test "-Dtest=WalkthroughSmokeTest,CrossUserIsolationTest"
```

- **WalkthroughSmokeTest** (13 steps) imports every sample, waits for background processing,
  then checks redaction, duplicate skipping, decision extraction, search, cited answers,
  autocomplete, drift alerts, superseding, "where you stopped", context packs, agent files,
  daily limits and error responses. Each step is its own test, so a failure names the feature.
- **CrossUserIsolationTest** (7 tests) proves one user gets 404 for everything belonging to another.

Both create fresh test users, so you can run them as often as you like.

## 6. Start the web app

Needs Node.js 20.9 or newer. With the API still running:

```bash
cd web
npm install
npm run dev
```

Open http://localhost:3000 and use **Sign in** under "Local development". Import the files
in `samples/` to see the flow end to end.

## 7. Try Explain

Zip the sample codebase and upload it under **Explain** in the web app:

```powershell
Compress-Archive -Path samples\billing-service -DestinationPath billing-service.zip   # Windows
```

```bash
cd samples && zip -r ../billing-service.zip billing-service                             # macOS, Linux
```

Import `samples/code-review-chat.md` too, and the file views show the conversation behind
`src/lib/db.ts` and `src/events/store.ts`. The events module imports MongoDB, so with the
Postgres decision from the other samples it shows up as code that goes against a decision.

## 8. Optional: use real AI models

Without keys, Notch runs fully offline: answers quote the passages that match, decisions are found
by patterns, and "meaning" search compares words. With keys, Claude writes answers and finds
decisions, and Voyage embeddings make search understand meaning. Private projects are never sent
to either.

1. Create an API key at console.anthropic.com (add credits first) and one at
   dashboard.voyageai.com.
2. Set them in the terminal you start the API from. Never put keys in a file inside the
   repository, a commit, a chat or a screenshot.

   ```powershell
   $env:ANTHROPIC_API_KEY = "sk-ant-..."     # PowerShell; lasts until the terminal closes
   $env:VOYAGE_API_KEY = "pa-..."
   mvn -pl api spring-boot:run
   ```

   ```bash
   export ANTHROPIC_API_KEY=sk-ant-... VOYAGE_API_KEY=pa-...   # macOS, Linux
   ```

3. The API's log says what it's using: `Answers and extraction: Claude (claude-haiku-5-5)` and
   `Embeddings: voyage-4-lite-1024`. Existing passages are re-embedded in the background; until
   that finishes, meaning search only covers what's been converted.

Choose models with `NOTCH_LLM_MODEL` (default `claude-haiku-5-5`) and `NOTCH_EMBEDDING_MODEL`
(default `voyage-4-lite`). Force offline with `NOTCH_LLM=offline` or `NOTCH_EMBEDDINGS=offline`.
Costs are capped by the daily limits: 30 questions and 20 imports per user.

To compare search with real embeddings, run the evaluation with `VOYAGE_API_KEY` set:

```powershell
mvn -q -pl api test -Dtest=SearchEvaluationTest -Dnotch.eval.out=../docs/evaluation.md
```

`docs/evaluation.md` then gains a section comparing Voyage with the offline embeddings.

## 9. Try the guard and the MCP server

```bash
mvn -q -pl cli -am package
export PATH="$PWD/cli/bin:$PATH"
```

Create a personal token in the web app (Settings), then follow [`cli/README.md`](cli/README.md):
`notch login`, `notch link`, `notch hook git`, and try committing a `mongoose` dependency.

## If something fails

| Symptom | Likely cause | Fix |
|---|---|---|
| `release version 21 not supported` | Maven is using an older JDK | Point `JAVA_HOME` at JDK 21 |
| `Connection to localhost:5433 refused` | Database not running | `docker compose up -d`, wait for healthy |
| `port 5433 is already allocated` | Something else uses 5433 | Stop it, or change the port in `docker-compose.yml` and `DATABASE_URL` |
| `Port 8090 was already in use` | Something else on 8090 | Stop it, or run with `PORT=8091` and use that in `NOTCH_API_URL` |
| `type "vector" does not exist` | Plain Postgres instead of pgvector | Use the compose file's `pgvector/pgvector` image; `docker compose down -v` then up |
| Flyway `checksum mismatch` | A migration changed after it ran | Local only: `docker compose down -v` (deletes local data), then start again |
| Live tests say `dev-login needs ... local profile` | API started with another profile | Start without `SPRING_PROFILES_ACTIVE`, or set it to `local` |
| Live tests are all skipped | `NOTCH_API_URL` not set in that terminal | Set it as shown above |
| Web app says the server isn't reachable | API not running, or on another port | Start the API, or set `NOTCH_API_URL` in `web/.env.local` |

## What to send back if something breaks

1. The command you ran.
2. The **first** error in the output. For the API, that's usually the first block starting
   with `ERROR` or `Caused by:` in the terminal running `spring-boot:run`. For tests, the
   failing test's name and its message.

Paste that into the chat; that's enough to fix it.
