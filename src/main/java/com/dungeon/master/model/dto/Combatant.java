package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.CombatantKind;

import java.util.UUID;

/** One entry in the initiative order. {@code refId} is a player id or enemy id. */
public record Combatant(
        CombatantKind kind,
        UUID refId,
        String name,
        int initiative,
        int dexMod
) {
}
