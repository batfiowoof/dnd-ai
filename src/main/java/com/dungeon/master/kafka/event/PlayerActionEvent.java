package com.dungeon.master.kafka.event;

import com.dungeon.master.model.dto.TravelContext;

import java.util.UUID;

/**
 * @param turnEventId the persisted {@code TurnEvent} this action created — threaded through so
 *                    the DM narration is attached to the exact row by id (never "find latest").
 * @param travel      optional travel framing when this action is an overland journey (null otherwise);
 *                    steers the DM to narrate the trip and, on an encounter, spring combat.
 */
public record PlayerActionEvent(
        UUID sessionId,
        UUID playerId,
        String playerName,
        String action,
        int turnNumber,
        UUID turnEventId,
        TravelContext travel
) {
}
