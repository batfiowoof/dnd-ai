package com.dungeon.master.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A player's narrative action. {@code spendInspiration} pre-arms the player's Inspiration for any
 * check the AI DM rolls this turn (auto-rolls happen inside the single DM turn, so there is no longer
 * a separate roll prompt to toggle it on). Null/false means do not spend.
 */
public record PlayerActionRequest(
        @NotBlank(message = "Action is required") String action,
        Boolean spendInspiration
) {
}
