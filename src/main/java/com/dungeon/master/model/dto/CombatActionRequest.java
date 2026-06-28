package com.dungeon.master.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * WebSocket inbound: a player's combat action (attack target / use item / cast spell).
 * Only the fields for the chosen action are populated. For a spell cast, {@code spellName}
 * + {@code spellLevel} identify the spell (0 = cantrip) and {@code targetIds} are the chosen
 * enemy ids (offensive) or player ids (heal/buff). For an area-of-effect spell, {@code originX}/
 * {@code originY} are the cast point on the grid; the backend resolves the affected enemies
 * authoritatively from the spell's template and ignores {@code targetIds}. Both are null for
 * single-target casts (or when the encounter has no grid).
 */
public record CombatActionRequest(
        UUID targetEnemyId,
        String itemName,
        String spellName,
        Integer spellLevel,
        List<UUID> targetIds,
        Integer originX,
        Integer originY
) {
}
