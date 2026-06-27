-- ============================================
-- Host-configurable session settings
-- ============================================
-- Chosen by the host at session creation. turn_mode drives narrative turn handling;
-- difficulty scales encounters + the check DC band; dm_style/dm_length shape the
-- per-session "Session directives" prepended to the DM prompt; the toggles gate the
-- LLM combat/roll automation; collab_window_seconds drives the collaborative round window.

ALTER TABLE game_sessions
    ADD COLUMN turn_mode             VARCHAR(20)  NOT NULL DEFAULT 'COLLABORATIVE',
    ADD COLUMN difficulty            VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN dm_style              VARCHAR(20)  NOT NULL DEFAULT 'HEROIC',
    ADD COLUMN dm_length             VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN allow_ai_combat       BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN allow_ai_rolls        BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN collab_window_seconds INT          NOT NULL DEFAULT 10;
