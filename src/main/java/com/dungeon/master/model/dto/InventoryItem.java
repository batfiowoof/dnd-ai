package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.ItemKind;

/**
 * One stack of an inventory item. Stored as JSONB on player_runtime_state and on the
 * character template (starting_inventory). {@code equipped} is a display/context flag for
 * weapons and armor — it carries no mechanical effect (no AC recompute).
 */
public record InventoryItem(
        String name,
        int qty,
        ItemKind kind,
        boolean equipped
) {
    /** Convenience for the common case of an unequipped stack. */
    public InventoryItem(String name, int qty, ItemKind kind) {
        this(name, qty, kind, false);
    }
}
