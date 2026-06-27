package com.dungeon.master.model.dto;

import java.util.UUID;

/**
 * An open ability check carried on {@link GameStateDto} so a reconnecting client can re-open
 * the roll prompt (the {@code ROLL_REQUEST} WebSocket event is transient and missed on reload).
 * {@code suggestedModifier} is an informational ability-mod hint; the backend still rolls
 * authoritatively when the check resolves.
 *
 * <p>{@code checkKind} (STANDARD / GROUP / CONTEST) and {@code targetLabel} (the opposed party for
 * a CONTEST, null otherwise) let a reconnecting client frame the prompt correctly — without them a
 * CONTEST would re-open as a meaningless "DC 0" with no opponent. {@code dmMode} carries the DM's
 * situational ADVANTAGE/DISADVANTAGE grant (NORMAL when none) so the re-opened prompt can show the
 * advantage/disadvantage badge instead of silently dropping it on reload.
 */
public record PendingCheckDto(
        UUID playerId,
        String ability,
        int dc,
        String skill,
        String reason,
        int suggestedModifier,
        String checkKind,
        String targetLabel,
        String dmMode
) {
}
