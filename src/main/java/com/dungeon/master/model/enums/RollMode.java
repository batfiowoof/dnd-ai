package com.dungeon.master.model.enums;

/** How a dice expression is resolved. ADVANTAGE/DISADVANTAGE roll the set twice. */
public enum RollMode {
    NORMAL,
    ADVANTAGE,
    DISADVANTAGE;

    /**
     * Combine several roll-mode sources per D&D 5e RAW: advantage and disadvantage cancel.
     * If any source grants ADVANTAGE and any grants DISADVANTAGE → NORMAL; else any ADVANTAGE
     * → ADVANTAGE; else any DISADVANTAGE → DISADVANTAGE; else NORMAL. {@code null} sources are
     * treated as NORMAL (no effect), so callers can pass an absent mode directly.
     */
    public static RollMode combine(RollMode... sources) {
        boolean advantage = false;
        boolean disadvantage = false;
        if (sources != null) {
            for (RollMode m : sources) {
                if (m == ADVANTAGE) {
                    advantage = true;
                } else if (m == DISADVANTAGE) {
                    disadvantage = true;
                }
            }
        }
        if (advantage && disadvantage) {
            return NORMAL;
        }
        if (advantage) {
            return ADVANTAGE;
        }
        if (disadvantage) {
            return DISADVANTAGE;
        }
        return NORMAL;
    }
}
