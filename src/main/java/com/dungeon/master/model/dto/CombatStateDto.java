package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.CombatStatus;

import java.util.List;
import java.util.UUID;

/** Full combat snapshot — sent with every combat event so clients stay in sync. */
public record CombatStateDto(
        UUID encounterId,
        CombatStatus status,
        int round,
        int activeIndex,
        Combatant active,
        List<Combatant> order,
        List<EnemyDto> enemies,
        GridState grid
) {
}
