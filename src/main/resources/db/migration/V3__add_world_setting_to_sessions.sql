-- Add world_setting column to game_sessions for per-session world context
ALTER TABLE game_sessions
    ADD COLUMN world_setting TEXT;
