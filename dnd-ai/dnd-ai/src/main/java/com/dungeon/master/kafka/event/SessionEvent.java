package com.dungeon.master.kafka.event;

import java.util.UUID;

public record SessionEvent(
        UUID sessionId,
        UUID playerId,
        Type type
) {
    public enum Type {
        PLAYER_JOINED,
        PLAYER_LEFT,
        GAME_STARTED,
        GAME_ENDED
    }
}
