-- ============================================
-- Turn event source discriminator
-- ============================================
-- Combat beats are persisted as turn_events (so they flow through history + RAG like
-- narrative turns), but their `action` is a mechanical summary rather than a player's
-- spoken line. This column lets the client replay them faithfully — as narration, not as
-- a player-attributed chat bubble. Existing rows are narrative.

ALTER TABLE turn_events
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'NARRATIVE';
