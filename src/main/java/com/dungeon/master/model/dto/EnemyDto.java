package com.dungeon.master.model.dto;

import java.util.UUID;

/** Enemy snapshot for clients (HP bars + targeting). */
public record EnemyDto(
        UUID id,
        String name,
        int maxHp,
        int currentHp,
        int armorClass,
        boolean alive
) {
}
