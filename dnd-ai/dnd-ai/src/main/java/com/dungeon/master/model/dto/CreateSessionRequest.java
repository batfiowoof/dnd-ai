package com.dungeon.master.model.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank(message = "Player name is required") String playerName,
        @NotBlank(message = "Character name is required") String characterName
) {
}
