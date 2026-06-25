package com.dungeon.master.model.dto;

/** Inbound WebSocket payload for toggling the equipped flag on a weapon/armor item. */
public record EquipItemRequest(
        String name,
        boolean equipped
) {
}
