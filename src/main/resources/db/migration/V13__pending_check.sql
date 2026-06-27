-- ============================================
-- Pending ability checks (LLM-requested rolls)
-- ============================================
-- When the DM decides a narrative action's outcome is uncertain it emits a
-- [[ROLL: ability=… dc=… …]] tag instead of narrating success/failure. The backend
-- persists the request here (so it survives reconnects and is the authoritative source
-- of the DC) and prompts that player for a roll. Resolved checks are deleted.
-- Keyed by (session_id, player_id): at most one pending check per player at a time.

CREATE TABLE pending_checks (
    session_id    UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    player_id     UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    ability       VARCHAR(10) NOT NULL,
    dc            INT NOT NULL,
    skill         VARCHAR(50),
    reason        TEXT,
    turn_event_id UUID,
    round_token   UUID,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, player_id)
);

CREATE INDEX idx_pending_checks_session ON pending_checks(session_id);
