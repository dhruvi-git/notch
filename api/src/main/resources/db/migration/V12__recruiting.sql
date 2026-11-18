-- Recruiting: a hiring drive, its roles with seat limits, what each role requires, and
-- the seat assignments max-flow produced. Match scores are computed on request, not stored.

CREATE TABLE drives (
    id            UUID PRIMARY KEY,
    recruiter_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title         TEXT NOT NULL,
    description   TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX drives_recruiter_idx ON drives (recruiter_id, created_at DESC);

CREATE TABLE roles (
    id         UUID PRIMARY KEY,
    drive_id   UUID NOT NULL REFERENCES drives (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    seats      INT NOT NULL CHECK (seats BETWEEN 0 AND 10000),
    threshold  DOUBLE PRECISION NOT NULL CHECK (threshold BETWEEN 0 AND 100),
    position   INT NOT NULL
);

CREATE INDEX roles_drive_idx ON roles (drive_id, position);

CREATE TABLE role_requirements (
    role_id    UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    skill_id   INT NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    weight     DOUBLE PRECISION NOT NULL CHECK (weight > 0 AND weight <= 10),
    must_have  BOOLEAN NOT NULL,
    min_level  TEXT NOT NULL CHECK (min_level IN ('CLAIMED', 'SHOWN', 'VERIFIED')),
    PRIMARY KEY (role_id, skill_id)
);

CREATE TABLE assignment_runs (
    id                UUID PRIMARY KEY,
    drive_id          UUID NOT NULL REFERENCES drives (id) ON DELETE CASCADE,
    total_seats       INT NOT NULL,
    greedy_filled     INT NOT NULL,
    augmenting_paths  INT NOT NULL,
    millis            DOUBLE PRECISION NOT NULL,
    run_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX assignment_runs_drive_idx ON assignment_runs (drive_id, run_at DESC);

CREATE TABLE assignments (
    run_id   UUID NOT NULL REFERENCES assignment_runs (id) ON DELETE CASCADE,
    role_id  UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    user_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    score    DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (run_id, user_id)
);
