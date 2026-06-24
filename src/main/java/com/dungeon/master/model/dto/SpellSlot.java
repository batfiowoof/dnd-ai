package com.dungeon.master.model.dto;

/** Spell slots for one spell level: how many exist and how many are spent. */
public record SpellSlot(
        int level,
        int max,
        int used
) {
}
