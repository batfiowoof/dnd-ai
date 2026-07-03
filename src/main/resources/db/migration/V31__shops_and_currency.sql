-- Shops & currency: a numeric coin purse (copper) plus authored shops on a world, compiled onto a
-- session (mutable runtime copy). Follows the JSONB-on-aggregate convention used by quests/milestones.
-- Money moves to a real numeric balance so shop buy/sell can do exact 5e gp/sp/cp arithmetic; coin
-- rewards and starting coin are folded into this column instead of living as "150 GP" inventory items.

ALTER TABLE player_runtime_state ADD COLUMN copper BIGINT NOT NULL DEFAULT 0;
ALTER TABLE worlds        ADD COLUMN shops JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE game_sessions ADD COLUMN shops JSONB NOT NULL DEFAULT '[]'::jsonb;
