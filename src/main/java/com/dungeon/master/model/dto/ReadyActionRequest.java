package com.dungeon.master.model.dto;

import java.util.UUID;

/**
 * WebSocket inbound: the active player readies an attack against {@code targetEnemyId}. The swing
 * auto-fires as a reaction when that enemy first comes within the player's reach/range this round.
 */
public record ReadyActionRequest(UUID targetEnemyId) {
}
