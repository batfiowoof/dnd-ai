-- Rests advance the in-game clock and characters tire when they go without one.
--   hit_die_size             : the character's class hit die (d6/d8/d10/d12 → 6/8/10/12); a short rest
--                              spends Hit Dice, each healing (roll hit_die_size + CON mod).
--   hit_dice_total           : Hit Dice pool size = character level.
--   hit_dice_remaining       : Hit Dice still available to spend on a short rest (a long rest restores half).
--   exhaustion_level         : 5e exhaustion (0–6); +1 per 24h of in-game time without a long rest,
--                              −1 per long rest. Level 6 is death.
--   exhaustion_check_minutes : the in_game_minutes value marking the start of the current "awake" window;
--                              each full 1440 minutes past it accrues a level, and a long rest resets it.
-- All defaulted and additive; existing rows get a fresh, un-tired pool.

ALTER TABLE player_runtime_state ADD COLUMN hit_die_size             INT    NOT NULL DEFAULT 8;
ALTER TABLE player_runtime_state ADD COLUMN hit_dice_total           INT    NOT NULL DEFAULT 1;
ALTER TABLE player_runtime_state ADD COLUMN hit_dice_remaining       INT    NOT NULL DEFAULT 1;
ALTER TABLE player_runtime_state ADD COLUMN exhaustion_level         INT    NOT NULL DEFAULT 0;
ALTER TABLE player_runtime_state ADD COLUMN exhaustion_check_minutes BIGINT NOT NULL DEFAULT 0;
