package com.dungeon.master.model.dto;

import java.util.UUID;

/** WebSocket inbound: a player's combat action (attack target / use item / end turn). */
public record CombatActionRequest(
        UUID targetEnemyId,
        String itemName
) {
}
