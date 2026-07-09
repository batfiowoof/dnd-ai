package com.dungeon.master.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A player's narrative action. Heroic Inspiration and Lucky are no longer pre-armed here — they are
 * offered as an interactive reroll after a failed roll (see {@code RerollWindow}).
 */
public record PlayerActionRequest(
        @NotBlank(message = "Action is required") String action
) {
}
