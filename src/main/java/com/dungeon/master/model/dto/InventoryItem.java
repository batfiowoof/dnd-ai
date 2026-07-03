package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.EquipSlot;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.model.enums.ItemSubtype;

import java.util.Set;

/**
 * One stack of an inventory item. Stored as JSONB on player_runtime_state and on the
 * character template (starting_inventory). {@code equipped} is a display/context flag for
 * weapons and armor — it carries no mechanical effect (no AC recompute). {@code slot} is the
 * paper-doll slot the item currently occupies ({@code null} = backpack), kept consistent with
 * {@code equipped} ({@code equipped == (slot != null)}). {@code subtype} is optional and only
 * refines which slots the item may occupy; when {@code null} it is derived from {@code kind}.
 */
public record InventoryItem(
        String name,
        int qty,
        ItemKind kind,
        boolean equipped,
        EquipSlot slot,
        ItemSubtype subtype
) {
    /** Convenience for the common case of an unequipped stack. */
    public InventoryItem(String name, int qty, ItemKind kind) {
        this(name, qty, kind, false, null, null);
    }

    /** Convenience for a stack with only the legacy equipped flag set. */
    public InventoryItem(String name, int qty, ItemKind kind, boolean equipped) {
        this(name, qty, kind, equipped, null, null);
    }

    /** The slots this item may be equipped into — derived from its subtype, falling back to kind. */
    public Set<EquipSlot> allowedSlots() {
        return (subtype != null ? subtype : ItemSubtype.fromKind(kind)).allowedSlots();
    }

    /** A copy equipped into {@code newSlot} ({@code null} = moved back to the backpack). */
    public InventoryItem withSlot(EquipSlot newSlot) {
        return new InventoryItem(name, qty, kind, newSlot != null, newSlot, subtype);
    }

    /** A copy with a new quantity, preserving every other field (slot/subtype/equipped). */
    public InventoryItem withQty(int newQty) {
        return new InventoryItem(name, newQty, kind, equipped, slot, subtype);
    }
}
