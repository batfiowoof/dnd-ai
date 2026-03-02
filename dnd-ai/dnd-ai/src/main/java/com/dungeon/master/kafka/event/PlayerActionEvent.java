package com.dungeon.master.kafka.event;

import java.util.UUID;

public record PlayerActionEvent(
        UUID sessionId,
        UUID playerId,
        String playerName,
        String action,
        int turnNumber
) {
}
