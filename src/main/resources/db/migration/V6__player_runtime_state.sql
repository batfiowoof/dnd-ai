-- ============================================
-- Per-session runtime state for players
-- ============================================
-- Character (the `characters` table) is a reusable, user-owned template shared
-- across sessions. Volatile per-session values — current HP, used spell slots,
-- consumed potions, active conditions — must NOT mutate that template. They live
-- here, keyed by the per-session player row, and are seeded on join from the
-- linked character. The backend is the sole authority for these numbers.

CREATE TABLE player_runtime_state (
    player_id   UUID PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
    session_id  UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    current_hp  INT NOT NULL,
    max_hp      INT NOT NULL,
    temp_hp     INT NOT NULL DEFAULT 0,
    spell_slots JSONB NOT NULL DEFAULT '[]',
    inventory   JSONB NOT NULL DEFAULT '[]',
    conditions  JSONB NOT NULL DEFAULT '[]'
);

CREATE INDEX idx_player_runtime_state_session ON player_runtime_state(session_id);
