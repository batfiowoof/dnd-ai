-- Quest Designer: authored quests on a world, compiled onto a session (mutable runtime copy).
-- Follows the JSONB-on-aggregate convention used by milestones and custom monsters.
-- Gold rewards reuse the existing inventory system (GP as a GEAR item) — no currency column.

ALTER TABLE worlds        ADD COLUMN quests JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE game_sessions ADD COLUMN quests JSONB NOT NULL DEFAULT '[]'::jsonb;
