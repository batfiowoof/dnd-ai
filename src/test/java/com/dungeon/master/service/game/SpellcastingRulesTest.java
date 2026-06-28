package com.dungeon.master.service.game;

import com.dungeon.master.model.entity.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spell save DC / attack bonus per class and ability. */
class SpellcastingRulesTest {

    private Character caster(String clazz, int intel, int wis, int cha, int prof) {
        return Character.builder()
                .ownerUsername("u").name("C").race("Human").characterClass(clazz)
                .intelligence(intel).wisdom(wis).charisma(cha).proficiencyBonus(prof)
                .build();
    }

    @Test
    void wizardCastsWithIntelligence() {
        Character wiz = caster("Wizard", 16, 10, 10, 2); // INT +3
        assertEquals("INT", SpellcastingRules.castingAbility("Wizard"));
        assertEquals(3, SpellcastingRules.castingMod(wiz));
        assertEquals(13, SpellcastingRules.spellSaveDc(wiz));   // 8 + 2 + 3
        assertEquals(5, SpellcastingRules.spellAttackBonus(wiz)); // 2 + 3
    }

    @Test
    void clericCastsWithWisdom() {
        Character cleric = caster("Cleric", 10, 18, 10, 3); // WIS +4
        assertEquals("WIS", SpellcastingRules.castingAbility("Cleric"));
        assertEquals(4, SpellcastingRules.castingMod(cleric));
        assertEquals(15, SpellcastingRules.spellSaveDc(cleric)); // 8 + 3 + 4
    }

    @Test
    void bardCastsWithCharisma() {
        Character bard = caster("Bard", 10, 10, 14, 2); // CHA +2
        assertEquals("CHA", SpellcastingRules.castingAbility("Bard"));
        assertEquals(2, SpellcastingRules.castingMod(bard));
        assertEquals(12, SpellcastingRules.spellSaveDc(bard)); // 8 + 2 + 2
    }
}
