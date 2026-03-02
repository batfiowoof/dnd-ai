package com.dungeon.master.kafka.event;

import java.util.UUID;

public record DmResponseEvent(
        UUID sessionId,
        UUID playerId,
        String playerAction,
        String dmNarration,
        int turnNumber
) {
}
