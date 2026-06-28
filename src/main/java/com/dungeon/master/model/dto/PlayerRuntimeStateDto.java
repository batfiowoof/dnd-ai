package com.dungeon.master.model.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Snapshot of a player's per-session runtime state, sent to clients. */
public record PlayerRuntimeStateDto(
        UUID playerId,
        int currentHp,
        int maxHp,
        int tempHp,
        int armorClass,
        Map<String, Integer> abilities,
        List<SpellSlot> spellSlots,
        List<InventoryItem> inventory,
        List<String> conditions,
        List<String> cantrips,
        List<String> knownSpells,
        boolean inspiration,
        int deathSaveSuccesses,
        int deathSaveFailures,
        boolean stable,
        boolean dead
) {
}
