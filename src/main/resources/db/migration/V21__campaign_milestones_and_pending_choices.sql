-- DM-triggered milestone leveling.
-- Authored milestones live on the session; characters track levels whose ASI/spell
-- choices a player still owes after a milestone applied the mechanical advance.

ALTER TABLE game_sessions
    ADD COLUMN milestones JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE characters
    ADD COLUMN pending_choice_levels JSONB NOT NULL DEFAULT '[]'::jsonb;
