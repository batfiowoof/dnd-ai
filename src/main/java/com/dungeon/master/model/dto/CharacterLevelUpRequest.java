package com.dungeon.master.model.dto;

import jakarta.validation.Valid;

import java.util.List;

/**
 * Player choices applied when a character gains a level. {@code asi} is required only at
 * ASI levels (4/8/12/16/19) and ignored otherwise. {@code newCantrips}/{@code newSpells}
 * are spell names to append to the character's known lists (casters only). The new level
 * itself is derived server-side (current level + 1) and never trusted from the client.
 */
public record CharacterLevelUpRequest(
        @Valid AbilityScoreImprovement asi,
        List<String> newCantrips,
        List<String> newSpells
) {
}
