-- Bumped whenever a project's searchable content changes (imports, replaced decisions), so every
-- API instance can tell its in-memory index is stale and rebuild, not only the one that ran the job.
ALTER TABLE projects ADD COLUMN content_version BIGINT NOT NULL DEFAULT 0;
