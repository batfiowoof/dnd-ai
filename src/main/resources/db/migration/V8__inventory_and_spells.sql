-- ============================================
-- Spell selection + structured starting inventory
-- ============================================
-- Character creation now records the cantrips/spells a character knows and a
-- structured starting inventory (item name + quantity + kind). Known spells are
-- display-only for the character sheet (casting stays slot-level). The structured
-- inventory seeds the per-session runtime inventory directly, preserving real
-- quantities (e.g. 20 arrows) that the legacy `equipment` string list could not.

ALTER TABLE characters
    ADD COLUMN cantrips           JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN known_spells       JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN starting_inventory JSONB NOT NULL DEFAULT '[]';

-- Mirror the known-spell lists onto the runtime row so the in-game character sheet
-- can show spell names without re-reading the template.
ALTER TABLE player_runtime_state
    ADD COLUMN cantrips     JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN known_spells JSONB NOT NULL DEFAULT '[]';
