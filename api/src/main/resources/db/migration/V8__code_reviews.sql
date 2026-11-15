-- Review: a written assessment of a codebase (overview, issues, recommendations). It comes from
-- a zip reviewed on the server or from Claude Code reviewing the repository with /notch review.
-- The code is never stored, only the report, encrypted like everything else a user uploads.

CREATE TABLE code_reviews (
    id           UUID PRIMARY KEY,
    project_id   UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source       TEXT NOT NULL CHECK (source IN ('zip', 'claude-code')),
    label        TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    error        TEXT,
    issues       INT NOT NULL DEFAULT 0,
    high_issues  INT NOT NULL DEFAULT 0,
    payload      BYTEA,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);
CREATE INDEX code_reviews_project_idx ON code_reviews (project_id, created_at DESC);
