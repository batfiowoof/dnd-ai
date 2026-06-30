-- Homebrew monster stat blocks copied onto a session when it is started from a World.
-- They overlay the SRD MonsterCatalog for that session's combat (see MonsterResolver).

ALTER TABLE game_sessions
    ADD COLUMN custom_monsters JSONB NOT NULL DEFAULT '[]'::jsonb;
