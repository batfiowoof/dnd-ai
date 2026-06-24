package com.dungeon.master.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record JoinSessionRequest(
        @NotBlank(message = "Player name is required") String playerName,
        @NotNull(message = "Character ID is required") UUID characterId
) {
}
