# notch: the CLI

One jar, no dependencies beyond Java 21. It brings Notch's decisions to the places code gets
written:

- **Guard:** stops commits, Claude Code edits and pull requests that go against a decision.
- **MCP server:** gives Claude Code, Codex and Cursor the project's decisions and history,
  and lets them record new decisions as you make them.

Decisions are downloaded and checked **on your machine**: your code is never sent to Notch.
The last download is kept, so checks still work offline.

## Start here

```bash
notch login --token ntch_pat_...     # once per computer (token from Notch > Settings)
cd your-project && notch init        # once per repository
```

`notch init` links the repository to a Notch project (creating one named after the folder),
installs the hooks below, and captures your history: past Claude Code sessions in this
repository, the last 100 commits and the code map. After that, Notch keeps itself current:

| When | What's captured |
|---|---|
| A Claude Code session ends | That session: what you asked, what was answered, commands run, files edited, errors hit |
| You commit | The commit (message and files changed), any new sessions, and a refreshed code map, in the background |
| A Claude Code session starts | Claude gets a briefing: where you stopped, uncommitted work, next tasks, decisions in force |

Then, whenever you've lost the thread:

```bash
notch resume                          # where you stopped in this repository
notch why "ECONNREFUSED 127.0.0.1"    # have you seen this before, in any project?
```

Transcripts can include command output, so everything goes through Notch's import pipeline:
secrets are removed before storage, text is encrypted, and repeated passages are skipped with
the Bloom filter. Automatic captures have their own daily allowance (200), separate from manual
imports.

## Install

```bash
mvn -q -pl cli -am package            # builds cli/target/notch.jar
export PATH="$PWD/cli/bin:$PATH"      # cli/bin/notch finds the jar (notch.cmd on Windows)
```

Create a personal token in Notch (**Settings**), then in your repository:

```bash
notch login --token ntch_pat_... --api https://your-notch-api
notch link <project-id>               # from the project's address: /p/<project-id>/...
notch status
```

## Guard

```bash
notch hook git        # pre-commit: stops commits that go against a decision
notch hook claude     # Claude Code: stops edits and package installs that go against a decision, and tells Claude why
notch hook github     # GitHub Actions: annotates pull requests
```

What a stopped commit looks like:

```
Notch: 1 change goes against the project's decisions

  HIGH   package.json:5
         "mongoose": "^8.4.0"
         Adds MongoDB (mongoose), but on 8 Oct 2026 the project chose PostgreSQL and
         ruled it out: "Let's go with Postgres instead of MongoDB because we need joins…"

Commit stopped. If the decision has changed, replace it in Notch first.
To commit anyway: git commit --no-verify
```

The full run (login, link, hook, blocked commit, blocked Claude Code edit, an MCP session) is
in [`../docs/guard-demo.txt`](../docs/guard-demo.txt).

### How a change is judged

For each tool a decision ruled out, the engine's KMP matcher scans every added line for every
alias of that tool ("mongo", "mongoose", "pymongo", "spring-boot-starter-data-mongodb", ...),
keeping whole-word matches only.

| Where the line is | Ruled-out tool | Different tool of the same kind |
|---|---|---|
| Dependency manifest, import, connection string | HIGH (blocks) | MEDIUM |
| Other code | MEDIUM | not reported |
| Comments, docs, lockfiles, `AGENTS.md` / `CLAUDE.md` | ignored | ignored |

Short aliases such as `pg` only count in manifests and imports, where they're unambiguous.

`notch check` exit codes: `0` nothing blocking, `1` a HIGH finding (or any with `--strict`),
`3` the check couldn't run. The hooks **fail open**: if Notch can't run (no network and no
saved decisions, say), your commit or edit goes ahead and you see a warning.

Commits from an IDE often run without your shell's PATH, so the pre-commit hook tries `notch`
on PATH, then the jar it was installed from, and only skips the check (with a warning) when
neither is there. `notch hook claude` and `notch setup` do the same: if `notch` isn't on PATH
when you run them, they write the jar's full path into the config and tell you so.

A newer decision that chooses a tool lifts an older decision's ruling-out of that tool for the
guard; Drift still shows the pair so the older one can be marked as replaced.

### The Claude Code hook

`notch hook claude` adds a `PreToolUse` hook for `Edit`, `MultiEdit`, `Write`, `Bash` and `PowerShell` (how Claude Code runs commands on Windows) to
`.claude/settings.json`, keeping any settings already there (and replacing an older Notch
entry). Claude Code sends the call as JSON on stdin. For edits, Notch checks the new code; for
terminal commands, it reads package installs (`npm`, `pnpm`, `yarn`, `bun`, `pip`, `poetry`,
`uv`, `cargo`, `go get`, `gem`, `composer`, `docker run`) as the manifest lines they would add.
When something goes against a decision, Notch prints a `permissionDecision: "deny"` JSON
decision: Claude Code cancels the call and shows Claude the reason, so it raises the conflict
with you instead of working around it.

## Explain: map your codebase

```bash
notch map              # map this repository and upload the map
notch map --dry-run    # just show the summary
notch map --out map.json
```

The map is built **on your machine** from the files git tracks (so `.gitignore` is respected):
each file's role, summary, exports, endpoints, imports and the tools it uses, plus what to
read first, entry points and files that look unused. Only the map is uploaded: paths, names
and summaries, never code. Files that look like they hold secrets are listed by path; the
values are never read into the map.

## MCP server

```bash
notch setup claude     # writes .mcp.json
notch setup cursor     # writes .cursor/mcp.json
notch setup codex      # prints the ~/.codex/config.toml entry
```

The agent starts `notch mcp` itself and gets eight tools:

| Tool | What the agent uses it for |
|---|---|
| `notch_decisions` | The decisions in force, with ids, optionally by topic. Topics match kinds of tool too: "database" finds a Postgres decision |
| `notch_check_change` | Check code before writing it: the same guard as the hooks |
| `notch_search` | Search past conversations: an error, a file, why something was done |
| `notch_where_we_stopped` | The last session, open tasks and drift alerts |
| `notch_record_decision` | Record a decision the user just made, with why and what was ruled out |
| `notch_replace_decision` | Replace a decision in force, once the user confirms; the old one stays in history |
| `notch_explain_file` | What a file does, how it fits, what a change could affect, and the decisions and chats behind it |
| `notch_add_task` | Record follow-up work |

The server speaks MCP's newline-delimited JSON-RPC over stdio. Recording decisions from inside
the coding session means the ledger fills itself as you work.

## Settings

Highest priority first: command-line flags, then `NOTCH_API_URL`, `NOTCH_TOKEN` and
`NOTCH_PROJECT`, then the repository's `.notch` file (project id only, safe to commit), then
`~/.config/notch/config.properties` (written by `notch login`, readable only by you).
