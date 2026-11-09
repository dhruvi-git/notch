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
curl localhost:8080/actuator/health          # {"status":"UP"}
curl localhost:8080/auth/config              # {"github":false,"devLogin":true}
```

## 5. Run the live tests

In the second terminal:

macOS / Linux:
```bash
NOTCH_API_URL=http://localhost:8080 mvn -pl api test -Dtest='WalkthroughSmokeTest,CrossUserIsolationTest'
```

Windows (PowerShell):
```powershell
$env:NOTCH_API_URL="http://localhost:8080"; mvn -pl api test "-Dtest=WalkthroughSmokeTest,CrossUserIsolationTest"
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

## If something fails

| Symptom | Likely cause | Fix |
|---|---|---|
| `release version 21 not supported` | Maven is using an older JDK | Point `JAVA_HOME` at JDK 21 |
| `Connection to localhost:5432 refused` | Database not running | `docker compose up -d`, wait for healthy |
| `port 5432 is already allocated` | Another Postgres is running | Stop it, or change the port in `docker-compose.yml` and `DATABASE_URL` |
| `Port 8080 was already in use` | Something else on 8080 | Stop it, or run with `PORT=8081` and use that in `NOTCH_API_URL` |
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
