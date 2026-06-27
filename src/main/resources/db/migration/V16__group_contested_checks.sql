-- ============================================
-- Group & contested checks
-- ============================================
-- Extends pending_checks to carry the new check kinds the DM can request via tags:
--   * GROUP   — one check imposed on every player at once (shares a round_token); the party
--               succeeds iff at least half the participants succeed (resolved server-side).
--   * CONTEST — one player's check opposed by an NPC; the engine rolls BOTH sides (the NPC side
--               = 1d20 + target_mod) and compares totals, ties favouring the defender.
-- STANDARD is the existing single/collaborative ability check. created_at already exists (V13)
-- and is reused for the group-abandonment timeout sweep — no new timestamp column needed.

ALTER TABLE pending_checks ADD COLUMN check_kind   VARCHAR(20) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE pending_checks ADD COLUMN target_mod   INT;
ALTER TABLE pending_checks ADD COLUMN target_label VARCHAR(255);
