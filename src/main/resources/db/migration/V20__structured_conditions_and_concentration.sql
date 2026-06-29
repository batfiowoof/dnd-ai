-- Conditions become structured objects (name + source + duration) instead of bare strings,
-- so the combat engine can enforce, expire, and concentration-break them. Existing rows hold
-- ["restrained"]-style string arrays that no longer deserialize into ActiveCondition objects,
-- so reset both columns to empty (combat state is ephemeral per-encounter — safe to clear).
UPDATE enemies SET conditions = '[]'::jsonb;
UPDATE player_runtime_state SET conditions = '[]'::jsonb;

-- The caster's currently-sustained concentration spell (null when not concentrating).
ALTER TABLE player_runtime_state ADD COLUMN concentrating_spell TEXT;
