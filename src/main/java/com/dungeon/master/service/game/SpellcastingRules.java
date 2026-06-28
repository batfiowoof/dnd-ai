package com.dungeon.master.service.game;

import com.dungeon.master.model.entity.Character;

import java.util.Locale;

/**
 * D&D 5e spellcasting math for a player's combat casts: which ability a class
 * casts with, and the derived spell save DC / spell attack bonus. Kept separate
 * from {@code CombatService} so it can be unit-tested in isolation.
 */
public final class SpellcastingRules {

    private SpellcastingRules() {}

    /** The spellcasting ability (STR/DEX/CON/INT/WIS/CHA) for a class; defaults to INT. */
    public static String castingAbility(String charClass) {
        String c = charClass == null ? "" : charClass.toLowerCase(Locale.ROOT);
        return switch (c) {
            case "cleric", "druid", "ranger" -> "WIS";
            case "bard", "sorcerer", "warlock", "paladin" -> "CHA";
            default -> "INT"; // wizard, artificer, and any non-caster fallback
        };
    }

    /** Ability-score modifier for the class's spellcasting ability. */
    public static int castingMod(Character c) {
        if (c == null) return 0;
        int score = switch (castingAbility(c.getCharacterClass())) {
            case "WIS" -> c.getWisdom();
            case "CHA" -> c.getCharisma();
            default -> c.getIntelligence();
        };
        return Math.floorDiv(score - 10, 2);
    }

    /** Spell save DC = 8 + proficiency bonus + spellcasting modifier. */
    public static int spellSaveDc(Character c) {
        if (c == null) return 10;
        return 8 + c.getProficiencyBonus() + castingMod(c);
    }

    /** Spell attack bonus = proficiency bonus + spellcasting modifier. */
    public static int spellAttackBonus(Character c) {
        if (c == null) return 2;
        return c.getProficiencyBonus() + castingMod(c);
    }
}
