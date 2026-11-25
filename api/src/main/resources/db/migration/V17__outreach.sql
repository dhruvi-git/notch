-- Outreach: a recruiter contacts a candidate from a drive, the candidate sees it in Notch (and by
-- email when Notch can send mail and knows their address), and answers interested or not.

CREATE TABLE outreach (
    id            UUID PRIMARY KEY,
    drive_id      UUID NOT NULL REFERENCES drives (id) ON DELETE CASCADE,
    role_id       UUID REFERENCES roles (id) ON DELETE SET NULL,
    role_name     TEXT,
    recruiter_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    candidate_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject       TEXT NOT NULL,
    body          TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'SENT' CHECK (status IN ('SENT', 'INTERESTED', 'DECLINED')),
    delivery      TEXT NOT NULL CHECK (delivery IN ('IN_APP', 'EMAILED', 'EMAIL_FAILED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at  TIMESTAMPTZ,
    UNIQUE (drive_id, candidate_id)
);

CREATE INDEX outreach_candidate_idx ON outreach (candidate_id, created_at DESC);
