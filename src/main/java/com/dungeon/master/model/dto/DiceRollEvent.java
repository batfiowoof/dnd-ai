package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.RollMode;

import java.util.List;
import java.util.UUID;

/**
 * Broadcast to {@code /topic/game/{sessionId}} after the backend resolves a roll.
 * Every client animates the same predetermined {@code faces}, so the 3D dice land
 * on the authoritative values.
 */
public record DiceRollEvent(
        String type,
        UUID sessionId,
        UUID playerId,
        String playerName,
        String label,
        String notation,
        int count,
        int sides,
        int modifier,
        RollMode mode,
        List<Integer> faces,
        List<Integer> discarded,
        int total,
        boolean crit,
        boolean fumble
) {
    public static final String TYPE = "DICE_ROLL";

    public static DiceRollEvent of(UUID sessionId, UUID playerId, String playerName,
                                   String label, DiceRollResult r) {
        return new DiceRollEvent(
                TYPE, sessionId, playerId, playerName, label,
                r.notation(), r.count(), r.sides(), r.modifier(), r.mode(),
                r.faces(), r.discarded(), r.total(), r.crit(), r.fumble());
    }
}
