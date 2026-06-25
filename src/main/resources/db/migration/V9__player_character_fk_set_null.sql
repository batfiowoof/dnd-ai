-- ============================================
-- Allow deleting a character that has been used in sessions
-- ============================================
-- The players.character_id FK (added in V2) defaulted to ON DELETE RESTRICT, which
-- blocked deleting any character ever used in a session. A player row is a per-session
-- snapshot (character_name / character_sheet are denormalized onto it), so the session
-- record stays meaningful even after the source character is gone. Switch to SET NULL
-- so character deletion succeeds and the historical player row is preserved.

ALTER TABLE players DROP CONSTRAINT players_character_id_fkey;

ALTER TABLE players
    ADD CONSTRAINT players_character_id_fkey
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE SET NULL;
