package com.dungeon.master.model.dto;

import java.util.List;

/** Trimmed dice result embedded inside combat events (no DICE_ROLL broadcast). */
public record RollSummary(
        String notation,
        List<Integer> faces,
        int total,
        boolean crit,
        boolean fumble
) {
    public static RollSummary of(DiceRollResult r) {
        return new RollSummary(r.notation(), r.faces(), r.total(), r.crit(), r.fumble());
    }
}
