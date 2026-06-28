package com.dungeon.master.model.dto;

import java.util.UUID;

/**
 * WebSocket inbound: the active player spends their action to stabilize a dying ally.
 * {@code targetPlayerId} is the downed (0 HP, dying) player to attempt a DC 10 Medicine check on.
 */
public record StabilizeRequest(UUID targetPlayerId) {
}
