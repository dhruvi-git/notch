-- Explain: maps of a project's codebase. A map holds paths, imports, names and summaries,
-- never source code; it is still encrypted like everything else a user uploads.

CREATE TABLE code_maps (
    id          UUID PRIMARY KEY,
    project_id  UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source      TEXT NOT NULL CHECK (source IN ('cli', 'zip')),
    label       TEXT NOT NULL,
    files       INT NOT NULL,
    payload     BYTEA NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX code_maps_project_idx ON code_maps (project_id, created_at DESC);
