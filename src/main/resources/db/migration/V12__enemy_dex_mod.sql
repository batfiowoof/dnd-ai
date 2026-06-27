-- ============================================
-- Enemy Dexterity modifier
-- ============================================
-- D&D 5e initiative is 1d20 + DEX modifier for EVERY participant. Enemies previously
-- rolled a flat 1d20; this column lets them roll with their DEX mod (and gives the
-- deterministic initiative tiebreak a stable secondary key). Existing rows default to 0.

ALTER TABLE enemies
    ADD COLUMN dex_mod INT NOT NULL DEFAULT 0;
