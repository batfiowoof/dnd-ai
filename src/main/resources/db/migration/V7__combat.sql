-- ============================================
-- Combat: enemies + encounters
-- ============================================
-- Combat is an overlay on top of the narrative turn loop. While an encounter is
-- ACTIVE the session's narrative turn pointer (game_sessions.current_turn_player_id)
-- is frozen; combat tracks its own initiative order and active index here, and the
-- backend resolves all rolls and HP changes authoritatively.

CREATE TABLE enemies (
    id           UUID PRIMARY KEY,
    session_id   UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    max_hp       INT NOT NULL,
    current_hp   INT NOT NULL,
    armor_class  INT NOT NULL,
    attack_bonus INT NOT NULL,
    damage_dice  VARCHAR(20) NOT NULL,
    initiative   INT NOT NULL DEFAULT 0,
    alive        BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_enemies_session ON enemies(session_id);

CREATE TABLE combat_encounters (
    id               UUID PRIMARY KEY,
    session_id       UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    status           VARCHAR(20) NOT NULL,
    initiative_order JSONB NOT NULL DEFAULT '[]',
    active_index     INT NOT NULL DEFAULT 0,
    round            INT NOT NULL DEFAULT 1
);

CREATE INDEX idx_combat_encounters_session ON combat_encounters(session_id);
