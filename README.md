# Notch

Notch is the project brain every coding agent plugs into. `git blame` tells you who changed a
line; Notch tells you why. It keeps a ledger of your project's decisions from your chats and
coding sessions, gives every agent that ledger over MCP, and stops commits and agent edits
that go against it.

CIA 3 project for Advanced Algorithm, Semester V, B.Tech CS AI & ML (2026–27).

## Repository layout

| Path | What it is | Status |
|---|---|---|
| `engine/` | Self-implemented algorithms, tests and benchmarks. Plain Java 21, no framework | Done |
| `guard/` | Understands decisions and checks code changes against them. Shared by the API and CLI | Done |
| `cli/` | `notch`: pre-commit, Claude Code and pull-request guards, plus an MCP server for coding agents | Done |
| `api/` | Spring Boot REST API: GitHub sign-in, imports, hybrid search, cited answers, decision ledger, drift alerts, agent files | Done (phase 2) |
| `web/` | Next.js web app: landing, Recall, Where you stopped, Ledger, Drift, Sync, Imports, Lab, Settings | Done (phase 3) |
| `docs/` | Benchmark results and design notes | Ongoing |

**First time?** Follow [`RUNNING.md`](RUNNING.md): setup, tests, and what to do if something fails.

## Prerequisites

- JDK 21
- Maven 3.9 or newer
- Docker Desktop (for the local database)

## Run the API

```bash
docker compose up -d
mvn -q install -DskipTests
mvn -pl api spring-boot:run
```

See [`api/README.md`](api/README.md) for a walkthrough with the sample data in `samples/`.

## Run the tests

```bash
mvn test
```

## Run the benchmarks

```bash
mvn -q -pl engine compile
java -Xmx2500m -cp engine/target/classes dev.notch.engine.bench.BenchmarkRunner
```

The latest results are in [`docs/benchmarks.md`](docs/benchmarks.md).
