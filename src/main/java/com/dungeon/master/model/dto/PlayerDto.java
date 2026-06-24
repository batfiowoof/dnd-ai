package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.PlayerRole;

import java.util.Map;
import java.util.UUID;

public record PlayerDto(
        UUID id,
        String username,
        String characterName,
        PlayerRole role,
        int turnIndex,
        UUID characterId,
        Map<String, Object> characterSheet
) {
}
