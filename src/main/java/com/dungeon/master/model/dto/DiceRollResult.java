package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.RollMode;

import java.util.List;

/**
 * Deterministic outcome of a dice expression, computed authoritatively on the
 * backend. {@code faces} holds the individual die results that count toward the
 * total (after any advantage/disadvantage selection). {@code crit}/{@code fumble}
 * apply only to a single d20.
 */
public record DiceRollResult(
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
}
