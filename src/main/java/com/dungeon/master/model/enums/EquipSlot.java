package com.dungeon.master.model.enums;

/**
 * The eight equipment slots of the paper-doll. An item may occupy at most one slot, and a
 * slot holds at most one item. Which slots an item may occupy is derived from its
 * {@link ItemSubtype} (see {@code ItemSubtype#allowedSlots()}).
 */
public enum EquipSlot {
    HEAD,
    NECK,
    CHEST,
    HANDS,
    MAIN_HAND,
    OFF_HAND,
    FEET,
    RING
}
