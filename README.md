# Notch

Notch is the decision record for AI-assisted projects. It captures decisions from any chat,
syncs them into the files coding agents read (`CLAUDE.md`, `AGENTS.md`, Cursor rules), and
flags new work that contradicts them.

CIA 3 project for Advanced Algorithm, Semester V, B.Tech CS AI & ML (2026–27).

## Repository layout

| Path | What it is | Status |
|---|---|---|
| `engine/` | Self-implemented algorithms, tests and benchmarks. Plain Java 21, no framework | Done |
| `api/` | Spring Boot REST API that uses the engine | Next |
| `web/` | Next.js frontend | After the API |
| `docs/` | Benchmark results and design notes | Ongoing |

## Prerequisites

- JDK 21
- Maven 3.9 or newer

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
