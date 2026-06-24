package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.RollMode;

/** WebSocket inbound: a player asks the backend to roll dice. */
public record RollRequest(
        String label,
        String notation,
        RollMode mode
) {
}
