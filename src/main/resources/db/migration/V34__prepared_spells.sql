-- Spell preparation (2024 SRD): prepared casters (cleric, druid, wizard, paladin) prepare a subset
-- of their known leveled spells, sized by spellcasting modifier + level; only prepared spells (plus
-- cantrips) can be cast. Known casters keep every known spell prepared.
--   prepared_spells : JSONB array of the leveled spell names currently prepared (a subset of
--                     known_spells). Gates casting (see CombatService/GameWebSocketController).
--   prepared_max    : the preparation cap for this character (0 for classes that don't prepare).
-- Additive and defaulted. Existing rows backfill prepared = known so mid-session casters keep
-- casting exactly what they knew before; prepared_max stays 0 until a re-seed (no prepare UI for
-- those rows, which is harmless because everything known is already prepared).

ALTER TABLE player_runtime_state ADD COLUMN prepared_spells JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE player_runtime_state ADD COLUMN prepared_max INT NOT NULL DEFAULT 0;

UPDATE player_runtime_state SET prepared_spells = known_spells;
