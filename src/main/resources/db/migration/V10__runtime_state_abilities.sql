-- ============================================
-- Snapshot ability scores + armor class onto the per-session runtime state
-- ============================================
-- The client needs each player's AC (for the hover tooltip) and ability scores (for the
-- character-sheet dialog). These were never sent because they didn't live on the runtime
-- state. Like cantrips/known_spells, they are snapshotted from the Character template on
-- join, so an edit to the template doesn't retroactively change an in-progress session.

ALTER TABLE player_runtime_state
    ADD COLUMN armor_class INT NOT NULL DEFAULT 10,
    ADD COLUMN abilities   JSONB NOT NULL DEFAULT '{}';
