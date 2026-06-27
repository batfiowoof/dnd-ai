package com.dungeon.master.model.dto;

import java.util.UUID;

/**
 * Broadcast to {@code /topic/game/{sessionId}} when the DM requests an ability check from a
 * player. The targeted client opens a roll dialog (normal / advantage / disadvantage); the
 * backend then rolls authoritatively and narrates the outcome. The DC here is informational —
 * the persisted {@link com.dungeon.master.model.entity.PendingCheck} is the source of truth.
 */
public record RollRequestEvent(
        String type,
        UUID sessionId,
        UUID playerId,
        String ability,
        int dc,
        String skill,
        String reason,
        int suggestedModifier
) {
    public static final String TYPE = "ROLL_REQUEST";

    public static RollRequestEvent of(UUID sessionId, UUID playerId, String ability, int dc,
                                      String skill, String reason, int suggestedModifier) {
        return new RollRequestEvent(TYPE, sessionId, playerId, ability, dc, skill, reason, suggestedModifier);
    }
}
