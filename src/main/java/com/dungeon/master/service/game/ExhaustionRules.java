package com.dungeon.master.service.game;

import com.dungeon.master.model.enums.RollMode;

/**
 * Pure D&D 5e exhaustion math. Exhaustion is a 0–6 track a character accrues by going without a long
 * rest; each level piles on the previous level's effects. Stateless and side-effect-free — the single
 * source for what a given exhaustion level does mechanically, so the roll, combat, and rest paths all
 * enforce the same rules.
 *
 * <p>Effects by level (SRD): 1 disadvantage on ability checks · 2 speed halved · 3 disadvantage on
 * attack rolls and saving throws · 4 hit point maximum halved · 5 speed reduced to 0 · 6 death.
 */
public final class ExhaustionRules {

    private ExhaustionRules() {}

    /** The exhaustion track is capped at 6; reaching 6 kills the creature. */
    public static final int MAX_LEVEL = 6;

    /** One full day of in-game time (minutes) with no long rest accrues a level of exhaustion. */
    public static final long MINUTES_PER_LEVEL = 24 * 60;

    private static int clamp(int level) {
        return Math.max(0, Math.min(MAX_LEVEL, level));
    }

    /** Disadvantage on ability checks from level 1 (else NORMAL). */
    public static RollMode checkMode(int level) {
        return clamp(level) >= 1 ? RollMode.DISADVANTAGE : RollMode.NORMAL;
    }

    /** Disadvantage on attack rolls and saving throws from level 3 (else NORMAL). */
    public static RollMode attackAndSaveMode(int level) {
        return clamp(level) >= 3 ? RollMode.DISADVANTAGE : RollMode.NORMAL;
    }

    /** Effective walking speed: halved at level 2, reduced to 0 at level 5 (feet). */
    public static int effectiveSpeed(int baseSpeed, int level) {
        int l = clamp(level);
        if (l >= 5) {
            return 0;
        }
        if (l >= 2) {
            return baseSpeed / 2;
        }
        return baseSpeed;
    }

    /** Effective hit point maximum: halved (rounded down, min 1) from level 4. */
    public static int effectiveMaxHp(int baseMaxHp, int level) {
        if (clamp(level) >= 4) {
            return Math.max(1, baseMaxHp / 2);
        }
        return baseMaxHp;
    }

    /** Level 6 exhaustion is death. */
    public static boolean isDeadly(int level) {
        return clamp(level) >= MAX_LEVEL;
    }

    /** Short human summary of a level's mechanical effect, for the DM prompt and UI tooltips. */
    public static String describe(int level) {
        return switch (clamp(level)) {
            case 0 -> "not exhausted";
            case 1 -> "disadvantage on ability checks";
            case 2 -> "disadvantage on ability checks; speed halved";
            case 3 -> "disadvantage on ability checks, attacks, and saving throws; speed halved";
            case 4 -> "disadvantage on ability checks, attacks, and saves; speed halved; HP maximum halved";
            case 5 -> "disadvantage on ability checks, attacks, and saves; speed 0; HP maximum halved";
            default -> "dead from exhaustion";
        };
    }
}
