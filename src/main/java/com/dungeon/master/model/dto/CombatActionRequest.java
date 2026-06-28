package com.dungeon.master.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * WebSocket inbound: a player's combat action (attack target / use item / cast spell).
 * Only the fields for the chosen action are populated. For a spell cast, {@code spellName}
 * + {@code spellLevel} identify the spell (0 = cantrip) and {@code targetIds} are the chosen
 * enemy ids (offensive) or player ids (heal/buff).
 */
public record CombatActionRequest(
        UUID targetEnemyId,
        String itemName,
        String spellName,
        Integer spellLevel,
        List<UUID> targetIds
) {
}
