package com.dungeon.master.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CharacterCreateUpdateRequest(
        @NotBlank(message = "Character name is required") String name,
        @NotBlank(message = "Race is required") String race,
        @NotBlank(message = "Class is required") String characterClass,
        @Min(1) @Max(20) int level,
        String background,
        String alignment,
        @NotNull @Min(1) @Max(30) int strength,
        @NotNull @Min(1) @Max(30) int dexterity,
        @NotNull @Min(1) @Max(30) int constitution,
        @NotNull @Min(1) @Max(30) int intelligence,
        @NotNull @Min(1) @Max(30) int wisdom,
        @NotNull @Min(1) @Max(30) int charisma,
        int hitPoints,
        int armorClass,
        int speed,
        List<String> equipment,
        List<String> proficiencies,
        List<String> features,
        String backstory,
        String imageUrl
) {
}
