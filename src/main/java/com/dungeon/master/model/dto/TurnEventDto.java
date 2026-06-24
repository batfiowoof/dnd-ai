package com.dungeon.master.model.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record TurnEventDto(
        UUID id,
        UUID playerId,
        String playerName,
        String action,
        String dmResponse,
        LocalDateTime timestamp,
        int turnNumber
) {
}
