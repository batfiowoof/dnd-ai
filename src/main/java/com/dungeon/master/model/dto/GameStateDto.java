package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.GameStatus;

import java.util.List;
import java.util.UUID;

public record GameStateDto(
        UUID sessionId,
        String joinCode,
        GameStatus status,
        List<PlayerDto> players,
        UUID currentTurnPlayerId,
        int turnNumber,
        String createdBy,
        String worldSetting
) {
}
