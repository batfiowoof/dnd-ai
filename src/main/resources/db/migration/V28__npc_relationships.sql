-- NPC relationships: track how each NPC feels about the party over a playthrough, and let the host
-- gate whether the AI DM may change those feelings.

-- Host toggle, mirroring the allow_ai_* flags from V14. Default TRUE so existing sessions keep the
-- feature on unless the host opts out.
ALTER TABLE game_sessions
    ADD COLUMN allow_ai_disposition BOOLEAN NOT NULL DEFAULT TRUE;

-- Per-session NPC disposition (runtime state, like player_runtime_state / enemies — NOT authored
-- World data). One row per NPC per session, seeded from the authored baseline at session creation.
--   disposition : current signed attitude score in [-100, 100] (band derived in code).
--   baseline    : the authored starting score, kept for reference.
-- npc_name is copied by value (NPCs have no id); uniqueness is enforced case-insensitively per session.
CREATE TABLE npc_disposition (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID         NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    npc_name    VARCHAR(255) NOT NULL,
    disposition INT          NOT NULL DEFAULT 0,
    baseline    INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_npc_disposition_session ON npc_disposition(session_id);
CREATE UNIQUE INDEX ux_npc_disposition_session_name ON npc_disposition(session_id, lower(npc_name));
