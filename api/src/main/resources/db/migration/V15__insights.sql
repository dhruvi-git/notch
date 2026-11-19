-- What a candidate's read is built from, beyond scores.
--
-- Repositories: when the candidate's own commits happened, as counts only (the commits themselves
-- are never stored). Weekdays run Monday..Sunday and hours 0..23, in the zone set by
-- notch.insights.zone (default Asia/Kolkata).
ALTER TABLE evidence
    ADD COLUMN first_active_at    TIMESTAMPTZ,
    ADD COLUMN commit_weekdays    INT[] CHECK (commit_weekdays IS NULL OR cardinality(commit_weekdays) = 7),
    ADD COLUMN commit_hours       INT[] CHECK (commit_hours IS NULL OR cardinality(commit_hours) = 24),
    -- AI history: a chat export file, or the reply to Notch's memory prompt pasted back in.
    ADD COLUMN chat_source        TEXT CHECK (chat_source IN ('EXPORT', 'MEMORY')),
    -- How the candidate relates to the skill in that history: asked about it, is learning it, or uses it.
    ADD COLUMN chat_mode          TEXT CHECK (chat_mode IN ('ASKED', 'LEARNING', 'USED')),
    ADD COLUMN chat_conversations INT NOT NULL DEFAULT 0 CHECK (chat_conversations >= 0),
    ADD COLUMN chat_note          TEXT CHECK (chat_note IS NULL OR length(chat_note) <= 200);

-- Chat exports imported before V15: they came from files, and each one counted conversations.
UPDATE evidence SET chat_source = 'EXPORT', chat_mode = 'ASKED' WHERE kind = 'CHAT_EXPORT';

-- The role a candidate is aiming for, in their own words. Kept apart from profiles so it can be
-- set before they pick a handle.
CREATE TABLE candidate_goals (
    user_id    UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    goal       TEXT NOT NULL CHECK (length(goal) BETWEEN 1 AND 80),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
