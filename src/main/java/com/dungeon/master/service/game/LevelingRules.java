package com.dungeon.master.service.game;

import java.util.Set;

/**
 * Pure D&D 5E character-advancement math: proficiency bonus, fixed hit-point gains,
 * and Ability Score Improvement levels. Stateless and side-effect-free — the
 * authoritative source for what a character gains on level-up. Class hit dice are read
 * from the SRD corpus by the caller (see {@code CharacterService}); this class only
 * turns a hit-die size into its fixed per-level value.
 */
public final class LevelingRules {

    private LevelingRules() {}

    /** Highest reachable character level (SRD). */
    public static final int MAX_LEVEL = 20;

    /** No ability score may be raised above this via an Ability Score Improvement. */
    public static final int ASI_CAP = 20;

    /** Character levels that grant an Ability Score Improvement (SRD: 4, 8, 12, 16, 19). */
    private static final Set<Integer> ASI_LEVELS = Set.of(4, 8, 12, 16, 19);

    /** Proficiency bonus for a level: +2 at 1–4, +3 at 5–8, +4 at 9–12, +5 at 13–16, +6 at 17–20. */
    public static int proficiencyBonusForLevel(int level) {
        int clamped = Math.max(1, Math.min(level, MAX_LEVEL));
        return 2 + (clamped - 1) / 4;
    }

    /** PHB "fixed" hit points for a hit die taken after level 1 (d6→4, d8→5, d10→6, d12→7). */
    public static int fixedHpForHitDie(int hitDie) {
        return hitDie / 2 + 1;
    }

    /** True if reaching {@code level} grants an Ability Score Improvement. */
    public static boolean isAsiLevel(int level) {
        return ASI_LEVELS.contains(level);
    }

    /** D&D ability modifier: floor((score - 10) / 2). */
    public static int abilityMod(int score) {
        return Math.floorDiv(score - 10, 2);
    }
}
