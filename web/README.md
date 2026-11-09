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
npm run mock-api             # terminal 1: an in-memory stand-in on :8080
npm run dev                  # terminal 2
```

The mock API (`scripts/mock-api.mjs`) is a development tool only: it covers the screens built
so far with sample data, keeps everything in memory, and never ships with the app.

| Variable | Default | Notes |
|---|---|---|
| `NOTCH_API_URL` | `http://localhost:8080` | Read on the server only |

Checks: `npm test` (unit tests for highlighting and answer parsing; Node 22.6 or newer),
`npm run typecheck` and `npm run build`.

## Screens so far

- **Recall** (the default view): results update as you type; **Ask** writes an answer whose
  every line cites a source, with the quoted span highlighted in context. Matches are
  highlighted as whole words, the search explains how it read the query (prefixes for stems,
  tool names for expansions, time windows), and each result can show "why this result": the
  four ranking signals from the engine's hybrid ranker. `@` suggests files, errors and chat
  titles from the radix tree; arrow keys, Enter and Esc work as expected. The query stays in
  the address bar, so searches can be shared.
- **Ledger:** decisions in force with what they ruled out, tasks you can tick off, fixes, and
  replaced decisions. Replacing a decision (with an existing one or a new one) keeps its
  history, shown oldest first. Decisions and tasks can be recorded by hand.
- **Imports** and **Conversations**, from phase 3a.

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

The product's promise is that every claim shows its source, so the one strong visual element
is the highlighter: quoted source text is marked in yellow with a citation number. Everything
else stays quiet.

| Token | Light | Dark | Use |
|---|---|---|---|
| `--surface` | `#edeff5` fog | `#171b30` night indigo | Page |
| `--ink` | `#272c42` graphite | `#e6e8f2` | Text |
| `--ink-soft` | `#565c7a` | `#a6abc6` | Secondary text (AA contrast) |
| `--accent` | `#4b52d1` | `#8f95f7` | Only things you can act on |
| `--mark` | `#f2e25c` | `#f2e25c` | Only highlighted source text |

- **Type:** Schibsted Grotesk for everything; Fragment Mono only where the content is code
  (file names, commands, error text). Both are self-hosted from npm. The scale is a 1.2 ratio
  from 16px.
- **Layout:** a narrow rail (project, sections, account) and a left-aligned content column
  kept near 70 characters for reading. Lists use dividers rather than a card per row; panels
  are reserved for things you act on.
- **Themes:** light and dark are equal, follow the system by default, and can be fixed in the
  rail. The choice is stored in a cookie and applied on the server, so pages never flash.
- **Quality floor:** visible keyboard focus, reduced motion respected, `aria-current` on the
  active section, live regions for upload progress, and layouts that hold down to 360px.

## Structure

```
src/app/            routes: sign-in, auth handlers, API proxy, projects, project sections
src/components/     Shell (rail), Brand, ProjectSwitcher, NavLinks, UserMenu, ThemeToggle
src/lib/            API clients, types mirroring the Java records, formatting helpers
scripts/            mock API for interface work
tests/              unit tests for text helpers (node:test)
```
