-- End-of-session narrative recap + cross-session campaign continuity.
--   recap / recap_generated_at   : the chronicle written when a session ends (one cheap LLM call).
--   world_id                     : soft link to the saved World this session was started from, so a
--                                  later session from the same World can recall the last playthrough.
--   continued_from_session_id    : soft link to the finished session this one explicitly continues.
-- All nullable; no FK constraints — worlds are copied by value into a session, and the session link
-- is a soft reference (the prior row may be pruned independently).

ALTER TABLE game_sessions
    ADD COLUMN recap TEXT,
    ADD COLUMN recap_generated_at TIMESTAMP,
    ADD COLUMN world_id UUID,
    ADD COLUMN continued_from_session_id UUID;

CREATE INDEX idx_game_sessions_world ON game_sessions(world_id);
