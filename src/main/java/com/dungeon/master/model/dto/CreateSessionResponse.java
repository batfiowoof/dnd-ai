package com.dungeon.master.model.dto;

import java.util.UUID;

public record CreateSessionResponse(
        UUID sessionId,
        String joinCode,
        UUID playerId
) {
}
