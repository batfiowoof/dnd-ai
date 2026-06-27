package com.dungeon.master.kafka.event;

import java.util.UUID;

/**
 * @param turnEventId the persisted {@code TurnEvent} this action created — threaded through so
 *                    the DM narration is attached to the exact row by id (never "find latest").
 */
public record PlayerActionEvent(
        UUID sessionId,
        UUID playerId,
        String playerName,
        String action,
        int turnNumber,
        UUID turnEventId
) {
}
