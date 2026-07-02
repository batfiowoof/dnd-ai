-- Out-of-combat travel system: track where the party is and how much in-game time has passed.
--   current_region   : name of the party's current location (matches a World region name; null until
--                       set — free-text-world sessions never get one and simply hide the map).
--   in_game_minutes  : elapsed in-game time, advanced by each travel leg; rendered as Day N • HH:MM.
--   travel_pace      : the last-chosen overland pace (FAST | NORMAL | SLOW).
-- All nullable/defaulted and additive — no FK (region names are copied by value on the World).

ALTER TABLE game_sessions
    ADD COLUMN current_region VARCHAR(255),
    ADD COLUMN in_game_minutes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN travel_pace VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
