-- Notch initial schema.
-- Every content table carries user_id so each query can be scoped to its owner.
-- Message text and memory payloads are AES-256-GCM ciphertext (bytea), bound to the owner.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id          UUID PRIMARY KEY,
    github_id   BIGINT UNIQUE,
    handle      TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_tokens (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          TEXT NOT NULL,
    token_hash    BYTEA NOT NULL UNIQUE,  -- SHA-256 of the token; the token itself is never stored
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ
);

CREATE TABLE projects (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    is_private  BOOLEAN NOT NULL DEFAULT false,  -- private projects never reach an LLM
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

CREATE TABLE imports (
    id                     UUID PRIMARY KEY,
    project_id             UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id                UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    filename               TEXT,
    format                 TEXT NOT NULL,
    status                 TEXT NOT NULL CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    conversations_added    INT NOT NULL DEFAULT 0,
    conversations_updated  INT NOT NULL DEFAULT 0,
    messages_added         INT NOT NULL DEFAULT 0,
    messages_skipped       INT NOT NULL DEFAULT 0,
    chunks_added           INT NOT NULL DEFAULT 0,
    duplicate_chunks       INT NOT NULL DEFAULT 0,
    bloom_false_positives  INT NOT NULL DEFAULT 0,
    secrets_redacted       INT NOT NULL DEFAULT 0,
    memories_extracted     INT NOT NULL DEFAULT 0,
    error                  TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at            TIMESTAMPTZ
);
CREATE INDEX imports_project_idx ON imports (project_id, created_at DESC);

CREATE TABLE conversations (
    id           UUID PRIMARY KEY,
    project_id   UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source       TEXT NOT NULL,           -- chatgpt, claude, markdown, text
    external_id  TEXT NOT NULL,           -- id from the export, or a content hash for files
    title        TEXT NOT NULL,           -- secret-scanned, stored in plain text
    started_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, source, external_id)
);

CREATE TABLE messages (
    id               UUID PRIMARY KEY,
    conversation_id  UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    project_id       UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    import_id        UUID REFERENCES imports (id) ON DELETE SET NULL,
    external_id      TEXT NOT NULL,
    seq              INT NOT NULL,
    role             TEXT NOT NULL,
    body             BYTEA NOT NULL,      -- encrypted, secrets already redacted
    char_length      INT NOT NULL,
    sent_at          TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, external_id)
);
CREATE INDEX messages_import_idx ON messages (import_id);
CREATE INDEX messages_project_idx ON messages (project_id, sent_at);

-- A chunk is a span of one message. Its text is never stored twice: it is re-read from the
-- (decrypted) message using start_offset and end_offset.
CREATE TABLE chunks (
    id            UUID PRIMARY KEY,
    project_id    UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    message_id    UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    start_offset  INT NOT NULL,
    end_offset    INT NOT NULL,
    content_hash  BYTEA NOT NULL,         -- SHA-256 of normalized text; the exact duplicate check
    token_count   INT NOT NULL,
    embedding     vector(1024),           -- null until embedded, and always null in private projects
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_offset > start_offset),
    UNIQUE (project_id, content_hash)
);
CREATE INDEX chunks_message_idx ON chunks (message_id);
CREATE INDEX chunks_embedding_idx ON chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE memories (
    id                 UUID PRIMARY KEY,
    project_id         UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id            UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind               TEXT NOT NULL CHECK (kind IN ('DECISION', 'TASK', 'FIX', 'CONTEXT')),
    status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'DONE')),
    payload            BYTEA NOT NULL,    -- encrypted JSON: title, body, rationale, rejected options
    superseded_by      UUID REFERENCES memories (id) ON DELETE SET NULL,
    source_message_id  UUID REFERENCES messages (id) ON DELETE SET NULL,
    source_start       INT,
    source_end         INT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX memories_project_idx ON memories (project_id, kind, status);

CREATE TABLE jobs (
    id           BIGSERIAL PRIMARY KEY,
    kind         TEXT NOT NULL,
    project_id   UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    import_id    UUID REFERENCES imports (id) ON DELETE CASCADE,
    status       TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts     INT NOT NULL DEFAULT 0,
    run_after    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ
);
CREATE INDEX jobs_pending_idx ON jobs (status, run_after);

-- Daily allowances. Incremented with one atomic upsert so concurrent requests cannot overshoot.
CREATE TABLE daily_usage (
    user_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day      DATE NOT NULL,
    action   TEXT NOT NULL,
    used     INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day, action)
);
