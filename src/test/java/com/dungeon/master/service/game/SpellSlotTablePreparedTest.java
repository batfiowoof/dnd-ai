package com.dungeon.master.service.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spell-preparation caps: which classes prepare and how many spells they may prepare. */
class SpellSlotTablePreparedTest {

    @Test
    void preparedCastersAreClericDruidWizardPaladin() {
        assertTrue(SpellSlotTable.isPreparedCaster("Cleric"));
        assertTrue(SpellSlotTable.isPreparedCaster("druid"));
        assertTrue(SpellSlotTable.isPreparedCaster("Wizard"));
        assertTrue(SpellSlotTable.isPreparedCaster("Paladin"));
    }

    @Test
    void knownCastersAndMartialsDoNotPrepare() {
        assertFalse(SpellSlotTable.isPreparedCaster("Bard"));
        assertFalse(SpellSlotTable.isPreparedCaster("Sorcerer"));
        assertFalse(SpellSlotTable.isPreparedCaster("Ranger"));
        assertFalse(SpellSlotTable.isPreparedCaster("Warlock"));
        assertFalse(SpellSlotTable.isPreparedCaster("Fighter"));
        assertFalse(SpellSlotTable.isPreparedCaster(null));
    }

    @Test
    void fullPreparedCasterCountIsModPlusLevel() {
        // Wizard level 5, INT +3 → 8 prepared.
        assertEquals(8, SpellSlotTable.preparedCount("Wizard", 3, 5));
        // Cleric level 1, WIS +2 → 3 prepared.
        assertEquals(3, SpellSlotTable.preparedCount("Cleric", 2, 1));
    }

    @Test
    void paladinPreparesOffHalfLevel() {
        // Paladin level 5, CHA +3 → 3 + floor(5/2)=2 → 5 prepared.
        assertEquals(5, SpellSlotTable.preparedCount("Paladin", 3, 5));
    }

    @Test
    void preparedCountIsAtLeastOneForCastersAndZeroForNonCasters() {
        // A negative modifier still leaves at least one prepared spell.
        assertEquals(1, SpellSlotTable.preparedCount("Cleric", -3, 1));
        // Non-preparing classes always return 0.
        assertEquals(0, SpellSlotTable.preparedCount("Bard", 5, 10));
        assertEquals(0, SpellSlotTable.preparedCount("Fighter", 3, 5));
    }
}
