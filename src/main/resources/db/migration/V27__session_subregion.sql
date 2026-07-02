-- Two-level travel: track which subregion within the current region the party is at.
--   current_subregion : name of the party's current subregion (matches a nested World subregion name;
--                       null when the party is at the region generally, or the world has no subregions).
-- Additive and nullable — no FK (subregion names are copied by value on the World, scoped to a region).
-- Cleared whenever the party travels to a new region; set by local (intra-region) moves.

ALTER TABLE game_sessions
    ADD COLUMN current_subregion VARCHAR(255);
