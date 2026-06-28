ALTER TABLE combat_encounters ADD COLUMN grid_state JSONB;

ALTER TABLE player_runtime_state ADD COLUMN death_save_successes INT NOT NULL DEFAULT 0;
ALTER TABLE player_runtime_state ADD COLUMN death_save_failures  INT NOT NULL DEFAULT 0;
ALTER TABLE player_runtime_state ADD COLUMN stable               BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE player_runtime_state ADD COLUMN dead                 BOOLEAN NOT NULL DEFAULT false;
