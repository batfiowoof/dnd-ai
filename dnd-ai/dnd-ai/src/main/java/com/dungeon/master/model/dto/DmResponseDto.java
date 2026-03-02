package com.dungeon.master.model.dto;

import java.util.UUID;

public record DmResponseDto(
        UUID sessionId,
        UUID playerId,
        String playerAction,
        String dmNarration,
        UUID nextTurnPlayerId,
        int turnNumber
) {
}
