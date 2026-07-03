package com.dungeon.master.model.enums;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Fine-grained item type used only to decide which {@link EquipSlot}s an item may occupy —
 * the coarse {@link ItemKind} can't tell a helmet from boots. Optional on an item: when
 * {@code null}, the allowed slots are derived from {@link ItemKind} via {@link #fromKind}.
 */
public enum ItemSubtype {
    HELMET(EquipSlot.HEAD),
    AMULET(EquipSlot.NECK),
    BODY_ARMOR(EquipSlot.CHEST),
    GLOVES(EquipSlot.HANDS),
    WEAPON(EquipSlot.MAIN_HAND, EquipSlot.OFF_HAND),
    SHIELD(EquipSlot.OFF_HAND),
    BOOTS(EquipSlot.FEET),
    RING(EquipSlot.RING),
    /** Not equippable — belongs in the backpack only. */
    OTHER();

    private final Set<EquipSlot> allowedSlots;

    ItemSubtype(EquipSlot... slots) {
        this.allowedSlots = slots.length == 0
                ? EnumSet.noneOf(EquipSlot.class)
                : EnumSet.copyOf(Arrays.asList(slots));
    }

    /** The slots an item of this subtype may be equipped into (empty = not equippable). */
    public Set<EquipSlot> allowedSlots() {
        return allowedSlots;
    }

    /** Fallback subtype for legacy items that carry no explicit subtype. */
    public static ItemSubtype fromKind(ItemKind kind) {
        return switch (kind) {
            case WEAPON -> WEAPON;
            case ARMOR -> BODY_ARMOR;
            default -> OTHER;
        };
    }
}
