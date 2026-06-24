package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.enums.RollMode;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiceServiceTest {

    private final DiceService dice = new DiceService();

    @Test
    void parsesNotationAndModifier() {
        DiceRollResult r = dice.roll("2d6+3");
        assertEquals(2, r.count());
        assertEquals(6, r.sides());
        assertEquals(3, r.modifier());
        assertEquals(2, r.faces().size());
        assertEquals("2d6+3", r.notation());
    }

    @Test
    void defaultsCountToOneAndHandlesNegativeModifier() {
        DiceRollResult r = dice.roll("d20-1");
        assertEquals(1, r.count());
        assertEquals(20, r.sides());
        assertEquals(-1, r.modifier());
        assertEquals(1, r.faces().size());
        assertEquals("1d20-1", r.notation());
    }

    @RepeatedTest(50)
    void rollStaysWithinBounds() {
        DiceRollResult r = dice.roll("3d8+2");
        for (int face : r.faces()) {
            assertTrue(face >= 1 && face <= 8, "face out of range: " + face);
        }
        int faceSum = r.faces().stream().mapToInt(Integer::intValue).sum();
        assertEquals(faceSum + 2, r.total());
        assertTrue(r.total() >= 5 && r.total() <= 26, "total out of range: " + r.total());
    }

    @RepeatedTest(50)
    void advantageKeepsHigherSet() {
        DiceRollResult r = dice.roll("1d20", RollMode.ADVANTAGE);
        assertEquals(RollMode.ADVANTAGE, r.mode());
        assertEquals(1, r.faces().size());
        assertEquals(1, r.discarded().size());
        assertTrue(r.faces().get(0) >= r.discarded().get(0));
    }

    @RepeatedTest(50)
    void disadvantageKeepsLowerSet() {
        DiceRollResult r = dice.roll("1d20", RollMode.DISADVANTAGE);
        assertTrue(r.faces().get(0) <= r.discarded().get(0));
    }

    @Test
    void normalRollHasNoDiscardedSet() {
        DiceRollResult r = dice.roll("1d20");
        assertNull(r.discarded());
    }

    @Test
    void critAndFumbleOnlyForSingleD20() {
        // A d6 can never be flagged crit/fumble even on a max/min face.
        for (int i = 0; i < 100; i++) {
            DiceRollResult r = dice.roll("1d6");
            assertTrue(!r.crit() && !r.fumble());
        }
    }

    @Test
    void rejectsInvalidNotation() {
        assertThrows(IllegalArgumentException.class, () -> dice.roll("hello"));
        assertThrows(IllegalArgumentException.class, () -> dice.roll("d1"));
        assertThrows(IllegalArgumentException.class, () -> dice.roll("0d6"));
        assertThrows(IllegalArgumentException.class, () -> dice.roll(null));
    }

    @Test
    void d1IsRejectedButD2IsAllowed() {
        DiceRollResult r = dice.roll("1d2");
        assertSame(RollMode.NORMAL, r.mode());
        assertTrue(r.faces().get(0) >= 1 && r.faces().get(0) <= 2);
    }
}
