# Notch web app

Next.js 16 (App Router) and React 19, written in TypeScript with plain CSS modules.

## Run it

Against the real API (start it first, see [`../RUNNING.md`](../RUNNING.md)):

```bash
cd web
npm install
npm run dev                  # http://localhost:3000
```

Without the Java backend, for working on the interface alone:

```bash
npm run mock-api             # terminal 1: an in-memory stand-in on :8090
npm run dev                  # terminal 2
```

The mock API (`scripts/mock-api.mjs`) is a development tool only: it covers the screens built
so far with sample data, keeps everything in memory, and never ships with the app.

| Variable | Default | Notes |
|---|---|---|
| `NOTCH_API_URL` | `http://localhost:8090` | Read on the server only |

Checks: `npm test` (unit tests for highlighting and answer parsing; Node 22.6 or newer),
`npm run typecheck` and `npm run build`.

## Screens

### Portfolios and hiring (the main product)

| Route | Who | What it does |
|---|---|---|
| `/` | Everyone | Landing: "Show, don't claim", evidence levels, the recruiter pitch |
| `/start` | Candidate | Connect GitHub, upload a ChatGPT or Claude export, add projects, approve suggested skills, publish |
| `/u/{handle}` | Public, no sign-in | Skills by area (score bar, evidence level, rank badge), proof-of-work cards, a share card that downloads as a 1080×1350 PNG |
| `/hire` | Recruiter | Drives, and a new drive from a pasted job description |
| `/hire/{id}` | Recruiter | Edit roles (seats, minimum match, required skills with weight, must-have, evidence level) beside the ranked shortlist; each score is broken down by skill |
| `/hire/{id}/assign` | Recruiter | The max-flow seat assignment, compared with best-score-first, with CSV export |

Signing in now lands on `/start`. The mock API covers all of these with a sample pool of
candidates (`scripts/mock-portfolio.mjs`), so the whole flow can be demonstrated without Java.

### Code projects (earlier features, still at `/projects`)

- **Recall** (the default view): results update as you type; **Ask** writes an answer whose
  every line cites a source, with the quoted span highlighted in context. Matches are
  highlighted as whole words, the search explains how it read the query (prefixes for stems,
  tool names for expansions, time windows), and each result can show "why this result": the
  four ranking signals from the engine's hybrid ranker. `@` suggests files, errors and chat
  titles from the radix tree; arrow keys, Enter and Esc work as expected. The query stays in
  the address bar, so searches can be shared.
- **Compare:** the ideation workbench. Options as columns, weighted criteria as rows, each
  cell scored 1 to 5, autosaved. The Java analyzer returns the weighted standings, what decides
  the lead, and how robust the winner is: a Monte Carlo run re-scores the matrix 2,000 times
  with every weight randomly varied between half and one-and-a-half times its value.
  "Record decision" puts the winner in the Ledger with the other options ruled out, so the
  guard and drift detection protect the choice from then on.
- **Explain:** a map of the project's codebase, from a zip upload or `notch map`. The overview
  shows where to start reading, the pages and endpoints where things begin, every tool the
  code relies on with the decision behind it, files that go against a decision, how the
  folders depend on each other, files that look unused, and files that look like they hold
  secrets. Each file's view answers what it does, how it fits (depends on, used by, what a
  change could affect) and why it's like this (the decisions it follows or goes against, and
  the conversation passages that mention it or its folders, highlighted). Try it with
  `samples/billing-service`.
- **Ledger:** decisions in force with what they ruled out, tasks you can tick off, fixes, and
  replaced decisions. Replacing a decision (with an existing one or a new one) keeps its
  history, shown oldest first. Decisions and tasks can be recorded by hand.
- **Where you stopped:** the last session (what you asked, how the reply began), open tasks in
  priority order (tick them off here), decisions in force, recent fixes, and a pointer to any
  drift.
- **Drift:** each alert shows both sides, with the evidence highlighted. Two disagreeing
  decisions are settled in one click (keep the newer or the earlier one, which replaces the
  other); a chat that uses something ruled out can be dismissed as intended. The rail shows how
  many alerts are open.
- **Sync:** a context pack (with a length slider) to paste into any chat, and `AGENTS.md` /
  `CLAUDE.md` previews with copy and download.
- **Imports** and **Conversations**.
- **Lab:** this project's index, autocomplete, threads and repeat-check numbers, plus the
  engine's benchmarks against their baselines at three sizes, drawn from
  `src/data/benchmarks.json`. Regenerate it after changing the engine:
  `java -Xmx2500m -cp engine/target/classes dev.notch.engine.bench.BenchmarkRunner --json > web/src/data/benchmarks.json`
- **Settings:** create and revoke personal tokens for the extensions; a new token is shown
  once.
- **Landing page** (`/` when signed out): a question types itself, the answer's source is
  highlighted with a marker stroke, then a drift warning appears. Reduced motion shows the end
  state at once.

## How it talks to the API

The browser never calls the API or sees a token. After sign-in, the session token lives in an
`httpOnly` cookie that page scripts cannot read, so a cross-site scripting bug could not steal
it.

- **Server components** call the API directly with the token (`src/lib/server-api.ts`).
- **Interactive components** call `/api/n/...`, a route handler that adds the token and
  streams the request through, file uploads included (`src/app/api/n/[...path]/route.ts`).
- **Sign-in:** `/auth/github` starts GitHub sign-in through the API; `/auth/callback` trades
  the one-time code for a session server-side; `/auth/dev` signs in a test user when the API
  runs locally.
- An expired session sends the person back to sign in with a short explanation.

## Design

Bold deep purple and signature yellow, from the project's reference boards. Purple is the brand
surface (the rail and the hero stages); vivid purple marks what you can act on; yellow is the
highlighter on quoted sources and marks where you are. Reading areas stay calm and white so
long quotes and data are comfortable for hours.

| Token | Light | Dark | Use |
|---|---|---|---|
| `--brand` | `#330069` | `#2d0a63` | Rail, Recall stage, landing hero, page headers |
| `--accent` | `#902afe` | `#b98cff` | Actions, links, chips, focus |
| `--mark` | `#fedc2a` | `#fedc2a` | Highlighted sources, current section, primary action on purple |
| `--surface` | `#fbf8ff` | `#160d24` | Page |
| `--ink` | `#2f253a` | `#f3eefa` | Text (13.8:1 on the page) |

- **Scoped tokens:** `.on-brand` redefines the same variables for purple stages, so the logo,
  menus, toggles and links placed on purple recolour themselves without per-component rules.
  Menus that float over purple use the `--pop-*` tokens.
- **Type:** Bricolage Grotesque 800 for headlines, set large and tight; Schibsted Grotesk for
  everything you read; Fragment Mono only for code, with ligatures off so code shows exactly
  what's in the file.
- **Shape:** radius follows hierarchy: 28px stages, 18px cards, 12px fields, pills for buttons,
  chips and tabs. Shadows only on things that float (the answer card, menus, the demo).
- **The memorable element:** Recall opens on a purple stage with a yellow headline and a white
  pill search bar; answers float below with their sources highlighted.
- **Quality floor:** every colour pair checked for WCAG AA contrast, visible keyboard focus,
  reduced motion respected, layouts down to 360px, light and dark as equal themes.

## Structure

```
src/app/            routes: sign-in, auth handlers, API proxy, projects, project sections
src/components/     Shell (rail), Brand, ProjectSwitcher, NavLinks, UserMenu, ThemeToggle
src/lib/            API clients, types mirroring the Java records, formatting helpers
scripts/            mock API for interface work
tests/              unit tests for text helpers (node:test)
```
