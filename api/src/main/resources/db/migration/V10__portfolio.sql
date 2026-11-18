-- Portfolios: a candidate's public profile, the evidence they connect, and the skills it backs.
-- Skill scores and levels are not stored: they are computed from evidence on every read.

CREATE TABLE profiles (
    user_id     UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    handle      TEXT NOT NULL UNIQUE CHECK (handle ~ '^[a-z0-9][a-z0-9-]{1,38}$'),
    headline    TEXT,
    domain      TEXT,
    is_public   BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE skills (
    id      INT PRIMARY KEY,
    name    TEXT NOT NULL UNIQUE,
    domain  TEXT NOT NULL
);

-- Other names for a skill ("postgres" for PostgreSQL). Matched after normalizing case and punctuation.
CREATE TABLE skill_aliases (
    alias     TEXT PRIMARY KEY,
    skill_id  INT NOT NULL REFERENCES skills (id) ON DELETE CASCADE
);

-- One piece of proof: a GitHub repository, a project or activity, or topics from a chat export.
CREATE TABLE evidence (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind            TEXT NOT NULL CHECK (kind IN ('GITHUB_REPO', 'PROJECT', 'CHAT_EXPORT')),
    title           TEXT NOT NULL,
    url             TEXT,
    description     TEXT,
    own_commits     INT NOT NULL DEFAULT 0 CHECK (own_commits >= 0),
    active_weeks    INT NOT NULL DEFAULT 0 CHECK (active_weeks >= 0),
    is_fork         BOOLEAN NOT NULL DEFAULT false,
    duplicate_of    UUID REFERENCES evidence (id) ON DELETE SET NULL,
    chat_depth      DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (chat_depth BETWEEN 0 AND 1),
    root_sha        TEXT,   -- first commit of a repo: catches re-uploads that keep history
    tree_sha        TEXT,   -- tree of its latest commit: catches exact copies pushed fresh
    last_active_at  TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX evidence_user_idx ON evidence (user_id);
CREATE INDEX evidence_root_sha_idx ON evidence (root_sha) WHERE root_sha IS NOT NULL;
CREATE INDEX evidence_tree_sha_idx ON evidence (tree_sha) WHERE tree_sha IS NOT NULL;

-- Which skills a piece of evidence backs. Links from chat exports start unapproved and do not
-- count until the candidate approves them.
CREATE TABLE evidence_skills (
    evidence_id  UUID NOT NULL REFERENCES evidence (id) ON DELETE CASCADE,
    skill_id     INT NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    approved     BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (evidence_id, skill_id)
);

-- Skills the candidate typed in themselves, and skills they chose to hide.
CREATE TABLE user_skills (
    user_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    skill_id  INT NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    claimed   BOOLEAN NOT NULL DEFAULT false,
    hidden    BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (user_id, skill_id)
);
