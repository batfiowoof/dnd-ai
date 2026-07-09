-- Magic-item attunement (SRD): a creature may be attuned to at most three magic items at once.
--   attuned_items : JSONB array of the item display names this player is attuned to. Attunement
--                   gates an attunement-required item's mechanical effects (see MagicItemEffects).
-- Additive and defaulted; existing rows start attuned to nothing.

ALTER TABLE player_runtime_state ADD COLUMN attuned_items JSONB NOT NULL DEFAULT '[]'::jsonb;
