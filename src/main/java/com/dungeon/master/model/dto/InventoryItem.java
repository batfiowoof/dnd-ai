package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.ItemKind;

/** One stack of an inventory item. Stored as JSONB on player_runtime_state. */
public record InventoryItem(
        String name,
        int qty,
        ItemKind kind
) {
}
