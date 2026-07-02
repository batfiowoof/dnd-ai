package com.dungeon.master.model.dto;

import java.util.UUID;

/**
 * Broadcast to {@code /topic/game/{sessionId}} whenever an NPC's disposition toward the party
 * changes, so the client relationship panel updates live. Mirrors {@link PlayerStateEvent}.
 */
public record NpcStateEvent(
        String type,
        UUID sessionId,
        NpcStateDto state
) {
    public static final String TYPE = "NPC_STATE";

    public static NpcStateEvent of(UUID sessionId, NpcStateDto state) {
        return new NpcStateEvent(TYPE, sessionId, state);
    }
}
