package com.dungeon.master.model.enums;

/**
 * How strongly a character is trained in a skill (or save). The 2024 rules add EXPERTISE (double the
 * proficiency bonus) and HALF (Bard's Jack of All Trades — half, rounded down) on top of plain
 * PROFICIENT. NONE means the ability modifier applies with no proficiency bonus.
 *
 * <p>The proficiency-bonus contribution is centralized here via {@link #bonus(int)} so the check/save
 * math has one source of truth (mirrored client-side in {@code frontend/src/lib/dnd5e.ts}).
 */
public enum ProficiencyLevel {
    NONE,
    HALF,
    PROFICIENT,
    EXPERTISE;

    /** The proficiency-bonus contribution at this level: NONE→0, HALF→pb/2 (floor), PROFICIENT→pb, EXPERTISE→2·pb. */
    public int bonus(int proficiencyBonus) {
        return switch (this) {
            case NONE -> 0;
            case HALF -> proficiencyBonus / 2;
            case PROFICIENT -> proficiencyBonus;
            case EXPERTISE -> proficiencyBonus * 2;
        };
    }
}
