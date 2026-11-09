-- Phase 2e: decision history, drift alerts the user dismissed.

CREATE INDEX memories_superseded_by_idx ON memories (superseded_by);

-- Drift alerts are computed on demand; only dismissals are stored.
CREATE TABLE drift_dismissals (
    project_id    UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    alert_key     TEXT NOT NULL,
    dismissed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, alert_key)
);
