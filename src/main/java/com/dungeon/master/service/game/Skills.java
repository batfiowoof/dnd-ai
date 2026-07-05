package com.dungeon.master.service.game;

import java.util.Locale;
import java.util.Map;

/**
 * Static D&D skill/ability lookups shared by the check-and-save math. The 18 skill→ability map mirrors
 * {@code frontend/src/lib/dnd5e.ts} (SKILL_ABILITIES); ability abbreviations are the STR/DEX/… keys used
 * throughout the runtime-state ability map.
 */
public final class Skills {

    private Skills() {}

    /** Canonical skill name → governing ability abbreviation ("Stealth" → "DEX"). */
    public static final Map<String, String> SKILL_ABILITIES = Map.ofEntries(
            Map.entry("acrobatics", "DEX"),
            Map.entry("animal handling", "WIS"),
            Map.entry("arcana", "INT"),
            Map.entry("athletics", "STR"),
            Map.entry("deception", "CHA"),
            Map.entry("history", "INT"),
            Map.entry("insight", "WIS"),
            Map.entry("intimidation", "CHA"),
            Map.entry("investigation", "INT"),
            Map.entry("medicine", "WIS"),
            Map.entry("nature", "INT"),
            Map.entry("perception", "WIS"),
            Map.entry("performance", "CHA"),
            Map.entry("persuasion", "CHA"),
            Map.entry("religion", "INT"),
            Map.entry("sleight of hand", "DEX"),
            Map.entry("stealth", "DEX"),
            Map.entry("survival", "WIS")
    );

    /** Governing ability abbreviation for a skill name, or null when the skill is unknown. */
    public static String abilityForSkill(String skill) {
        if (skill == null || skill.isBlank()) {
            return null;
        }
        return SKILL_ABILITIES.get(skill.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Ability abbreviation ("STR") for either a full display name ("Strength") or an already-short form.
     * Returns the uppercased input unchanged when it isn't a recognized full name (so "DEX" stays "DEX").
     */
    public static String abilityAbbrev(String ability) {
        if (ability == null || ability.isBlank()) {
            return null;
        }
        String a = ability.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "strength", "str" -> "STR";
            case "dexterity", "dex" -> "DEX";
            case "constitution", "con" -> "CON";
            case "intelligence", "int" -> "INT";
            case "wisdom", "wis" -> "WIS";
            case "charisma", "cha" -> "CHA";
            default -> ability.trim().toUpperCase(Locale.ROOT);
        };
    }
}
