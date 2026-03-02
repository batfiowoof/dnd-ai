package com.dungeon.master.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record CharacterCreateRequest(
        @NotNull(message = "Session ID is required") UUID sessionId,
        @NotBlank(message = "Character name is required") String characterName,
        Map<String, Object> characterSheet
) {
}
