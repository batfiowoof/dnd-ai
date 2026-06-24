package com.dungeon.master.kafka.event;

import java.util.UUID;

public record TurnNextEvent(
        UUID sessionId,
        UUID nextPlayerId,
        int turnNumber
) {
}
