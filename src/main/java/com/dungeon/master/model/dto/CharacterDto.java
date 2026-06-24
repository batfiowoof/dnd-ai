package com.dungeon.master.model.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CharacterDto(
        UUID id,
        String name,
        String race,
        String characterClass,
        int level,
        String background,
        String alignment,
        int strength,
        int dexterity,
        int constitution,
        int intelligence,
        int wisdom,
        int charisma,
        int hitPoints,
        int armorClass,
        int speed,
        int proficiencyBonus,
        List<String> equipment,
        List<String> proficiencies,
        List<String> features,
        String backstory,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
