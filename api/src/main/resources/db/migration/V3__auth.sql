-- Phase 2f: GitHub sign-in, session and personal tokens, one-time sign-in codes.

ALTER TABLE users ADD COLUMN avatar_url TEXT;

ALTER TABLE api_tokens
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'personal' CHECK (kind IN ('session', 'personal')),
    ADD COLUMN token_prefix TEXT NOT NULL DEFAULT '',
    ADD COLUMN expires_at TIMESTAMPTZ;
CREATE INDEX api_tokens_user_idx ON api_tokens (user_id, kind);

-- Handed to the web app after GitHub sign-in and traded once for a session token,
-- so the session token never appears in a URL.
CREATE TABLE auth_codes (
    code_hash   BYTEA PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ
);
