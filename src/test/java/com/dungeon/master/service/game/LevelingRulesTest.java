package com.dungeon.master.service.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D&D 5E character-advancement math: proficiency curve, fixed HP per die, ASI levels. */
class LevelingRulesTest {

    @Test
    void proficiencyBonusFollowsTheSrdCurve() {
        assertEquals(2, LevelingRules.proficiencyBonusForLevel(1));
        assertEquals(2, LevelingRules.proficiencyBonusForLevel(4));
        assertEquals(3, LevelingRules.proficiencyBonusForLevel(5));
        assertEquals(3, LevelingRules.proficiencyBonusForLevel(8));
        assertEquals(4, LevelingRules.proficiencyBonusForLevel(9));
        assertEquals(4, LevelingRules.proficiencyBonusForLevel(12));
        assertEquals(5, LevelingRules.proficiencyBonusForLevel(13));
        assertEquals(5, LevelingRules.proficiencyBonusForLevel(16));
        assertEquals(6, LevelingRules.proficiencyBonusForLevel(17));
        assertEquals(6, LevelingRules.proficiencyBonusForLevel(20));
    }

    @Test
    void proficiencyBonusClampsOutOfRangeLevels() {
        assertEquals(2, LevelingRules.proficiencyBonusForLevel(0));
        assertEquals(6, LevelingRules.proficiencyBonusForLevel(25));
    }

    @Test
    void fixedHpMatchesPhbAveragePerHitDie() {
        assertEquals(4, LevelingRules.fixedHpForHitDie(6));
        assertEquals(5, LevelingRules.fixedHpForHitDie(8));
        assertEquals(6, LevelingRules.fixedHpForHitDie(10));
        assertEquals(7, LevelingRules.fixedHpForHitDie(12));
    }

    @Test
    void asiIsGrantedOnlyAtTheCanonicalLevels() {
        for (int level = 1; level <= 20; level++) {
            boolean expected = level == 4 || level == 8 || level == 12 || level == 16 || level == 19;
            assertEquals(expected, LevelingRules.isAsiLevel(level), "level " + level);
        }
        assertTrue(LevelingRules.isAsiLevel(4));
        assertFalse(LevelingRules.isAsiLevel(20));
    }

    @Test
    void abilityModifierFloorsTowardNegativeInfinity() {
        assertEquals(-1, LevelingRules.abilityMod(8));
        assertEquals(0, LevelingRules.abilityMod(10));
        assertEquals(2, LevelingRules.abilityMod(14));
        assertEquals(5, LevelingRules.abilityMod(20));
    }
}
