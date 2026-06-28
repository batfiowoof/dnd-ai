package com.dungeon.master.service.ai;

import com.dungeon.master.model.dto.SpellEffect;
import com.dungeon.master.model.enums.SpellEffectType;
import com.dungeon.master.model.enums.SpellResolution;
import com.dungeon.master.model.enums.SpellTargetType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates the structured spell dataset loads machine-readable combat effects. */
class SpellCatalogTest {

    private static SpellCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = new SpellCatalog();
        catalog.load();
    }

    @Test
    void loadsSpellEffects() {
        assertFalse(catalog.isEmpty());
        assertTrue(catalog.summaries().size() > 300);
    }

    @Test
    void fireballIsAreaDexSaveHalf() {
        SpellEffect fb = catalog.effect("Fireball").orElseThrow();
        assertEquals(SpellEffectType.DAMAGE, fb.effectType());
        assertEquals(SpellTargetType.AREA, fb.targetType());
        assertEquals(SpellResolution.SAVE, fb.resolution());
        assertEquals("DEX", fb.saveAbility());
        assertEquals("8d6", fb.damageDice());
        assertTrue(fb.halfOnSave());
        assertEquals("1d6", fb.perSlotAbove());
    }

    @Test
    void cureWoundsHealsAllies() {
        SpellEffect cw = catalog.effect("cure wounds").orElseThrow();  // case-insensitive
        assertEquals(SpellEffectType.HEAL, cw.effectType());
        assertEquals(SpellTargetType.ALLY, cw.targetType());
        assertEquals("2d8", cw.healDice());
        assertTrue(cw.addCastingMod());
    }

    @Test
    void fireBoltIsSpellAttack() {
        SpellEffect fb = catalog.effect("Fire Bolt").orElseThrow();
        assertEquals(SpellResolution.SPELL_ATTACK, fb.resolution());
        assertEquals("1d10", fb.cantripDie());
    }
}
