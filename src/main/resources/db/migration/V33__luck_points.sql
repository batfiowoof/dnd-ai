-- Lucky feat (2024 SRD): a pool of Luck Points equal to the character's Proficiency Bonus, spent to
-- reroll a d20 and regained on a long rest. Seeded from the character's origin feat (see FeatEffects);
-- characters without the Lucky feat keep 0.
-- Additive and defaulted; existing rows start with no luck points.

ALTER TABLE player_runtime_state ADD COLUMN luck_points INT NOT NULL DEFAULT 0;
