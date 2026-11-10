-- Ideation workbench: decision matrices. The matrix itself is encrypted JSON, like memories.

CREATE TABLE comparisons (
    id           UUID PRIMARY KEY,
    project_id   UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status       TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DECIDED')),
    payload      BYTEA NOT NULL,
    decision_id  UUID REFERENCES memories (id) ON DELETE SET NULL,
    chosen       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX comparisons_project_idx ON comparisons (project_id, updated_at DESC);
