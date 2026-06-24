package com.dungeon.master.model.dto;

import java.util.UUID;

/**
 * Broadcast to {@code /topic/game/{sessionId}} whenever a player's runtime state
 * changes (HP, spell slots, inventory, conditions).
 */
public record PlayerStateEvent(
        String type,
        UUID sessionId,
        PlayerRuntimeStateDto state
) {
    public static final String TYPE = "PLAYER_STATE";

    public static PlayerStateEvent of(UUID sessionId, PlayerRuntimeStateDto state) {
        return new PlayerStateEvent(TYPE, sessionId, state);
    }
}
