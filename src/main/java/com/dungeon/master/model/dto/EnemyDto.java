package com.dungeon.master.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * Enemy snapshot for clients. Exact HP is intentionally hidden (5E "you don't know a monster's
 * HP") — only a coarse {@link HealthBand} is sent; the server/LLM keep the real numbers.
 */
public record EnemyDto(
        UUID id,
        String name,
        int armorClass,
        boolean alive,
        List<String> conditions,
        String healthBand,
        int reachFeet
) {
}
