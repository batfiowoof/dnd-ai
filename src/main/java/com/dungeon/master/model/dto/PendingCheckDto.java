package com.dungeon.master.model.dto;

import java.util.UUID;

/**
 * An open ability check carried on {@link GameStateDto} so a reconnecting client can re-open
 * the roll prompt (the {@code ROLL_REQUEST} WebSocket event is transient and missed on reload).
 * {@code suggestedModifier} is an informational ability-mod hint; the backend still rolls
 * authoritatively when the check resolves.
 */
public record PendingCheckDto(
        UUID playerId,
        String ability,
        int dc,
        String skill,
        String reason,
        int suggestedModifier
) {
}
