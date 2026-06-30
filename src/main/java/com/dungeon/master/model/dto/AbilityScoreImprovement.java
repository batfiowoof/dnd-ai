package com.dungeon.master.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A level-up Ability Score Improvement choice. Either +2 to a single ability
 * ({@code PLUS_TWO}, using {@code first}) or +1 to two different abilities
 * ({@code TWO_PLUS_ONE}, using {@code first} and {@code second}). Ability names are the
 * lower-cased canonical names: strength, dexterity, constitution, intelligence, wisdom,
 * charisma. The +20 cap and "two different abilities" rule are enforced server-side.
 */
public record AbilityScoreImprovement(
        @NotNull AsiMode mode,
        @NotBlank String first,
        String second
) {
    public enum AsiMode { PLUS_TWO, TWO_PLUS_ONE }
}
