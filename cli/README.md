# notch: the CLI

One jar, no dependencies beyond Java 21. It brings Notch's decisions to the places code gets
written:

- **Guard:** stops commits, Claude Code edits and pull requests that go against a decision.
- **MCP server:** gives Claude Code, Codex and Cursor the project's decisions and history,
  and lets them record new decisions as you make them.

Decisions are downloaded and checked **on your machine**: your code is never sent to Notch.
The last download is kept, so checks still work offline.

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
notch hook claude     # Claude Code: stops edits that go against a decision, and tells Claude why
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

### The Claude Code hook

`notch hook claude` adds a `PreToolUse` hook for `Edit`, `MultiEdit` and `Write` to
`.claude/settings.json`, keeping any settings already there. Claude Code sends the edit as
JSON on stdin; when the new code goes against a decision, Notch exits with code 2, which
blocks the edit and shows Claude the reason, so it can raise the conflict with you instead of
working around it. (There is a reported Claude Code issue where exit code 2 doesn't stop
`Bash` tool calls; Notch only hooks file edits, which aren't affected.)

## MCP server

```bash
notch setup claude     # writes .mcp.json
notch setup cursor     # writes .cursor/mcp.json
notch setup codex      # prints the ~/.codex/config.toml entry
```

The agent starts `notch mcp` itself and gets six tools:

| Tool | What the agent uses it for |
|---|---|
| `notch_decisions` | The decisions in force, optionally filtered by topic, before choosing a library or design |
| `notch_check_change` | Check code before writing it: the same guard as the hooks |
| `notch_search` | Search past conversations: an error, a file, why something was done |
| `notch_where_we_stopped` | The last session, open tasks and drift alerts |
| `notch_record_decision` | Record a decision the user just made, with why and what was ruled out |
| `notch_add_task` | Record follow-up work |

The server speaks MCP's newline-delimited JSON-RPC over stdio. Recording decisions from inside
the coding session means the ledger fills itself as you work.

## Settings

Highest priority first: command-line flags, then `NOTCH_API_URL`, `NOTCH_TOKEN` and
`NOTCH_PROJECT`, then the repository's `.notch` file (project id only, safe to commit), then
`~/.config/notch/config.properties` (written by `notch login`, readable only by you).
