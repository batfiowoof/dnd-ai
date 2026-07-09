-- Legendary & Lair actions (ROADMAP feature 8): boss drama for high-CR monsters.
--
-- Legendary actions are spent from a per-round budget at the end of each hero's turn; lair actions
-- fire once per round on initiative count 20, but only when the host started the encounter "in the
-- lair" — in that case EnemyFactory copies the monster's lair actions onto the enemy row, so a
-- non-empty lair_actions IS the "this fight has a lair" marker and no separate flag is needed.
-- Legendary Resistance turns a failed saving throw into a success; it is spent per encounter.
--
-- The action definitions themselves are hand-authored in resources/dnd5e/monster-actions.json and
-- copied onto the enemy at spawn (like the existing `attacks` column), because an Enemy row does
-- not retain its catalog key once its name has been scaled/suffixed ("Ancient Red Dragon 2").
--
-- All additive and defaulted; existing encounters keep no legendary actions and no lair.

ALTER TABLE enemies ADD COLUMN legendary_actions JSONB;
ALTER TABLE enemies ADD COLUMN lair_actions JSONB;
ALTER TABLE enemies ADD COLUMN legendary_action_max INT NOT NULL DEFAULT 0;
ALTER TABLE enemies ADD COLUMN legendary_actions_remaining INT NOT NULL DEFAULT 0;
ALTER TABLE enemies ADD COLUMN legendary_resistances INT NOT NULL DEFAULT 0;

-- The round whose lair action has already fired, so the count-20 hook is idempotent across the
-- reaction-window pause/resume cycle. 0 = none fired yet (combat rounds are 1-based).
ALTER TABLE combat_encounters ADD COLUMN lair_action_round INT NOT NULL DEFAULT 0;
