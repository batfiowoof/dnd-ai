-- ============================================
-- Add portrait image URL to characters
-- ============================================
-- Characters can carry an optional portrait, supplied as an external image URL
-- (no upload/storage infra). Surfaced in the character list, sheet, and in-game
-- (player sidebar + chat avatars).

ALTER TABLE characters
    ADD COLUMN image_url TEXT;
