package com.dungeon.master.model.dto;

import java.util.UUID;

/**
 * Live status of a collaborative round's collection window, broadcast to
 * {@code /topic/game/{sessionId}} on each submission/pass. Clients show a countdown and a
 * "submitted / total" indicator; the countdown is ticked down locally and re-synced on each
 * new event. {@code open == false} signals the window has flushed (the DM is now resolving).
 */
public record RoundStatusEvent(
        String type,
        UUID sessionId,
        int secondsLeft,
        int submitted,
        int total,
        boolean open
) {
    public static final String TYPE = "ROUND_STATUS";

    public static RoundStatusEvent of(UUID sessionId, int secondsLeft, int submitted, int total, boolean open) {
        return new RoundStatusEvent(TYPE, sessionId, secondsLeft, submitted, total, open);
    }
}
