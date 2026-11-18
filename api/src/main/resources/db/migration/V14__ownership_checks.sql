-- Ownership Checks: a timed session in which a candidate explains their own code, question by
-- question. A passed check makes the repository "defended", the strongest evidence level.

-- Recruiters can now require defended evidence.
ALTER TABLE role_requirements DROP CONSTRAINT role_requirements_min_level_check;
ALTER TABLE role_requirements ADD CONSTRAINT role_requirements_min_level_check
    CHECK (min_level IN ('CLAIMED', 'SHOWN', 'VERIFIED', 'DEFENDED'));

CREATE TABLE ownership_checks (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    evidence_id    UUID NOT NULL REFERENCES evidence (id) ON DELETE CASCADE,
    status         TEXT NOT NULL CHECK (status IN ('OPEN', 'FINISHED')),
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deadline       TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    score          INT CHECK (score BETWEEN 0 AND 100),
    defended       BOOLEAN NOT NULL DEFAULT false,
    summary        TEXT,
    questions_by   TEXT NOT NULL CHECK (questions_by IN ('model', 'templates')),
    graded_by      TEXT CHECK (graded_by IN ('model', 'rubric', 'mixed')),
    plan_elements  INT NOT NULL,   -- skills, concepts and files the check set out to touch
    plan_covered   INT NOT NULL,   -- how many of them the chosen questions touch (greedy set cover)
    CHECK (status = 'OPEN' OR finished_at IS NOT NULL)
);

CREATE INDEX ownership_checks_evidence_idx ON ownership_checks (evidence_id, finished_at DESC);
CREATE INDEX ownership_checks_user_idx ON ownership_checks (user_id, started_at DESC);

-- Text lists are stored one item per line; a rubric is one group per line, terms separated by '|'.
CREATE TABLE ownership_questions (
    id           UUID PRIMARY KEY,
    check_id     UUID NOT NULL REFERENCES ownership_checks (id) ON DELETE CASCADE,
    position     INT NOT NULL CHECK (position >= 0),
    path         TEXT NOT NULL,
    start_line   INT NOT NULL,
    end_line     INT NOT NULL,
    language     TEXT NOT NULL,
    code         TEXT NOT NULL,
    concept      TEXT,
    prompt       TEXT NOT NULL,
    rubric       TEXT NOT NULL,
    identifiers  TEXT NOT NULL,
    covers       TEXT NOT NULL,
    answer       TEXT,
    answered_at  TIMESTAMPTZ,
    millis       BIGINT CHECK (millis >= 0),
    pastes       INT NOT NULL DEFAULT 0 CHECK (pastes >= 0),
    focus_lost   INT NOT NULL DEFAULT 0 CHECK (focus_lost >= 0),
    score        INT CHECK (score BETWEEN 0 AND 100),
    feedback     TEXT,
    flags        TEXT,
    UNIQUE (check_id, position)
);
