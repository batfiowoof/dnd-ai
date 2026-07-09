package com.dungeon.master.model.dto;

/** WebSocket inbound: cast a spell of the given slot level (optional name/notation). */
public record CastRequest(
        int spellLevel,
        String spellName,
        String attackNotation,
        boolean ritual
) {
}
