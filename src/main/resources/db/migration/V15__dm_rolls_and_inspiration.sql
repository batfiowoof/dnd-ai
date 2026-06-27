-- ============================================
-- DM-aware rolls + Inspiration
-- ============================================
-- dm_mode persists the DM's situational ADVANTAGE/DISADVANTAGE on a pending check, applied
-- authoritatively when the player rolls. inspiration is a per-session flag a player may spend
-- on a roll for ADVANTAGE (their only roll-mode lever); spending clears it. Both are resolved
-- server-side — the LLM only requests via tags, it never changes an outcome.

ALTER TABLE pending_checks
    ADD COLUMN dm_mode VARCHAR(20);

ALTER TABLE player_runtime_state
    ADD COLUMN inspiration BOOLEAN NOT NULL DEFAULT FALSE;
