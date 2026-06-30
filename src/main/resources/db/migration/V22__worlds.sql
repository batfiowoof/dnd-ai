-- Player-authored, reusable campaign worlds (the structured form of a session's worldSetting).
-- Owned per-user; "compiled" into a game_session when a session is started from a world.
-- Structured sections are stored as JSONB lists, matching characters.starting_inventory and
-- game_sessions.milestones.

CREATE TABLE worlds (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_username  VARCHAR(255) NOT NULL,
    name            VARCHAR(120) NOT NULL,
    tagline         VARCHAR(255),
    overview        TEXT,
    tone            VARCHAR(100),
    magic_level     VARCHAR(100),
    regions         JSONB NOT NULL DEFAULT '[]'::jsonb,
    factions        JSONB NOT NULL DEFAULT '[]'::jsonb,
    npcs            JSONB NOT NULL DEFAULT '[]'::jsonb,
    custom_monsters JSONB NOT NULL DEFAULT '[]'::jsonb,
    milestones      JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_worlds_owner_updated ON worlds(owner_username, updated_at DESC);
