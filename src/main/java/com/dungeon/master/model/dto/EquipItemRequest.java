package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.EquipSlot;

/**
 * Inbound WebSocket payload to equip an item into a paper-doll slot. A {@code null} slot
 * unequips the named item back to the backpack.
 */
public record EquipItemRequest(
        String name,
        EquipSlot slot
) {
}
