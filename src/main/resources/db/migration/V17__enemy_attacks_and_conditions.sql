-- Combat rework: give enemies a full attack list (for multiattack monsters) and
-- a conditions list (so spells can debuff them). The legacy single attack_bonus /
-- damage_dice columns remain as a fallback for enemies seeded without a catalog
-- stat block. Both new columns are JSONB, matching the player_runtime_state pattern.

ALTER TABLE enemies
    ADD COLUMN attacks          JSONB   NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN conditions       JSONB   NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN abilities        JSONB   NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN attacks_per_turn INTEGER NOT NULL DEFAULT 1;
