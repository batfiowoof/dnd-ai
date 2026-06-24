-- ============================================
-- Characters (persistent, user-owned)
-- ============================================
CREATE TABLE characters (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_username  VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    race            VARCHAR(50)  NOT NULL,
    class           VARCHAR(50)  NOT NULL,
    level           INT          NOT NULL DEFAULT 1,
    background      VARCHAR(100),
    alignment       VARCHAR(50),

    -- Ability scores
    strength        INT NOT NULL DEFAULT 10,
    dexterity       INT NOT NULL DEFAULT 10,
    constitution    INT NOT NULL DEFAULT 10,
    intelligence    INT NOT NULL DEFAULT 10,
    wisdom          INT NOT NULL DEFAULT 10,
    charisma        INT NOT NULL DEFAULT 10,

    -- Derived / extra
    hit_points      INT NOT NULL DEFAULT 10,
    armor_class     INT NOT NULL DEFAULT 10,
    speed           INT NOT NULL DEFAULT 30,
    proficiency_bonus INT NOT NULL DEFAULT 2,

    -- Free-form JSON for equipment, proficiencies, features, etc.
    equipment       JSONB NOT NULL DEFAULT '[]'::jsonb,
    proficiencies   JSONB NOT NULL DEFAULT '[]'::jsonb,
    features        JSONB NOT NULL DEFAULT '[]'::jsonb,

    backstory       TEXT,

    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_characters_owner ON characters(owner_username);

-- ============================================
-- Track session creator for authorization
-- ============================================
ALTER TABLE game_sessions
    ADD COLUMN created_by VARCHAR(255);

-- ============================================
-- Link players to a persistent character
-- ============================================
ALTER TABLE players
    ADD COLUMN character_id UUID REFERENCES characters(id);
