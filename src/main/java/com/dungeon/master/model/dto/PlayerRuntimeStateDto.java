package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.ProficiencyLevel;

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
        Map<String, ProficiencyLevel> skillProficiencies,
        List<String> savingThrowProficiencies,
        List<SpellSlot> spellSlots,
        List<InventoryItem> inventory,
        List<String> conditions,
        List<String> cantrips,
        List<String> knownSpells,
        boolean inspiration,
        int luckPoints,
        int deathSaveSuccesses,
        int deathSaveFailures,
        boolean stable,
        boolean dead,
        String concentratingSpell,
        int exhaustionLevel,
        int hitDiceRemaining,
        int hitDiceTotal,
        long copper,
        List<String> attunedItems
) {
}
