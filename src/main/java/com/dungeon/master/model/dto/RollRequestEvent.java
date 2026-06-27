package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.RollMode;

import java.util.UUID;

/**
 * Broadcast to {@code /topic/game/{sessionId}} when the DM requests an ability check from a
 * player. The targeted client shows the pending check; the player's only roll-mode lever is
 * spending Inspiration (the free normal/advantage/disadvantage picker is gone). The backend
 * rolls authoritatively and narrates the outcome. The DC here is informational — the persisted
 * {@link com.dungeon.master.model.entity.PendingCheck} is the source of truth.
 *
 * <p>{@code dmMode} carries the DM's situational ADVANTAGE/DISADVANTAGE (NORMAL when none) so the
 * client can show e.g. "The DM grants you advantage"; {@code reason} explains why the roll was
 * called for.
 *
 * <p>{@code checkKind} marks STANDARD / GROUP / CONTEST so the client can frame the prompt (a group
 * roll vs a contested roll vs a normal one); {@code targetLabel} names the opposed party for a
 * CONTEST (null otherwise). The frontend consumes these in a later phase.
 */
public record RollRequestEvent(
        String type,
        UUID sessionId,
        UUID playerId,
        String ability,
        int dc,
        String skill,
        String reason,
        int suggestedModifier,
        RollMode dmMode,
        String checkKind,
        String targetLabel
) {
    public static final String TYPE = "ROLL_REQUEST";

    /** STANDARD check request (single or collaborative ability check). */
    public static RollRequestEvent of(UUID sessionId, UUID playerId, String ability, int dc,
                                      String skill, String reason, int suggestedModifier,
                                      RollMode dmMode) {
        return of(sessionId, playerId, ability, dc, skill, reason, suggestedModifier, dmMode,
                "STANDARD", null);
    }

    /** Request carrying an explicit check kind (GROUP / CONTEST) and optional contest target label. */
    public static RollRequestEvent of(UUID sessionId, UUID playerId, String ability, int dc,
                                      String skill, String reason, int suggestedModifier,
                                      RollMode dmMode, String checkKind, String targetLabel) {
        return new RollRequestEvent(TYPE, sessionId, playerId, ability, dc, skill, reason,
                suggestedModifier, dmMode == null ? RollMode.NORMAL : dmMode,
                checkKind == null ? "STANDARD" : checkKind, targetLabel);
    }
}
