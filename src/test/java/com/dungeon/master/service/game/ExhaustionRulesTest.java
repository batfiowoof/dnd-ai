package com.dungeon.master.service.game;

import com.dungeon.master.model.enums.RollMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExhaustionRulesTest {

    @Test
    void checksTakeDisadvantageFromLevelOne() {
        assertEquals(RollMode.NORMAL, ExhaustionRules.checkMode(0));
        assertEquals(RollMode.DISADVANTAGE, ExhaustionRules.checkMode(1));
        assertEquals(RollMode.DISADVANTAGE, ExhaustionRules.checkMode(5));
    }

    @Test
    void attacksAndSavesTakeDisadvantageFromLevelThree() {
        assertEquals(RollMode.NORMAL, ExhaustionRules.attackAndSaveMode(2));
        assertEquals(RollMode.DISADVANTAGE, ExhaustionRules.attackAndSaveMode(3));
    }

    @Test
    void speedHalvesAtTwoAndZeroesAtFive() {
        assertEquals(30, ExhaustionRules.effectiveSpeed(30, 1));
        assertEquals(15, ExhaustionRules.effectiveSpeed(30, 2));
        assertEquals(15, ExhaustionRules.effectiveSpeed(30, 4));
        assertEquals(0, ExhaustionRules.effectiveSpeed(30, 5));
    }

    @Test
    void maxHpHalvesFromLevelFour() {
        assertEquals(20, ExhaustionRules.effectiveMaxHp(20, 3));
        assertEquals(10, ExhaustionRules.effectiveMaxHp(20, 4));
        assertEquals(1, ExhaustionRules.effectiveMaxHp(1, 4), "never drops below 1");
    }

    @Test
    void levelSixIsDeath() {
        assertFalse(ExhaustionRules.isDeadly(5));
        assertTrue(ExhaustionRules.isDeadly(6));
    }
}
