package com.dungeon.master.model.dto;

import java.util.List;
import java.util.UUID;

/** Snapshot of a player's per-session runtime state, sent to clients. */
public record PlayerRuntimeStateDto(
        UUID playerId,
        int currentHp,
        int maxHp,
        int tempHp,
        List<SpellSlot> spellSlots,
        List<InventoryItem> inventory,
        List<String> conditions
) {
}
