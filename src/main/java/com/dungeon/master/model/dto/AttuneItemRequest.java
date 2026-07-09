package com.dungeon.master.model.dto;

/**
 * Inbound WebSocket payload to attune to (or end attunement with) a magic item the player holds,
 * addressed by its display name. Mirrors {@link EquipItemRequest}.
 */
public record AttuneItemRequest(
        String name
) {
}
