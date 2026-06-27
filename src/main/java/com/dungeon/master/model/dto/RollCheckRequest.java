package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.RollMode;

/**
 * WebSocket inbound: a player resolves the DM-requested ability check pending for them,
 * choosing normal / advantage / disadvantage. The backend computes the modifier and DC.
 */
public record RollCheckRequest(
        RollMode rollMode
) {
}
