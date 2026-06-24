package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.SpellSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Simplified D&D 5e spell-slot seeding. This is a deliberately reduced model (no
 * spell compendium, no preparation rules) — just slot counts per spell level so a
 * "cast a level-N spell" action can consume a resource. Full casters use the
 * standard table; half casters use it at half level; martials get none.
 */
final class SpellSlotTable {

    private SpellSlotTable() {}

    private static final Set<String> FULL_CASTERS =
            Set.of("wizard", "sorcerer", "cleric", "druid", "bard");
    private static final Set<String> HALF_CASTERS =
            Set.of("paladin", "ranger");
    // Warlock's pact magic is approximated as a half caster for this simplified model.
    private static final Set<String> PACT_CASTERS =
            Set.of("warlock");

    /** Standard full-caster slots[characterLevel 1..20][spellLevel 1..9]. */
    private static final int[][] FULL = {
            {2, 0, 0, 0, 0, 0, 0, 0, 0}, // 1
            {3, 0, 0, 0, 0, 0, 0, 0, 0}, // 2
            {4, 2, 0, 0, 0, 0, 0, 0, 0}, // 3
            {4, 3, 0, 0, 0, 0, 0, 0, 0}, // 4
            {4, 3, 2, 0, 0, 0, 0, 0, 0}, // 5
            {4, 3, 3, 0, 0, 0, 0, 0, 0}, // 6
            {4, 3, 3, 1, 0, 0, 0, 0, 0}, // 7
            {4, 3, 3, 2, 0, 0, 0, 0, 0}, // 8
            {4, 3, 3, 3, 1, 0, 0, 0, 0}, // 9
            {4, 3, 3, 3, 2, 0, 0, 0, 0}, // 10
            {4, 3, 3, 3, 2, 1, 0, 0, 0}, // 11
            {4, 3, 3, 3, 2, 1, 0, 0, 0}, // 12
            {4, 3, 3, 3, 2, 1, 1, 0, 0}, // 13
            {4, 3, 3, 3, 2, 1, 1, 0, 0}, // 14
            {4, 3, 3, 3, 2, 1, 1, 1, 0}, // 15
            {4, 3, 3, 3, 2, 1, 1, 1, 0}, // 16
            {4, 3, 3, 3, 2, 1, 1, 1, 1}, // 17
            {4, 3, 3, 3, 3, 1, 1, 1, 1}, // 18
            {4, 3, 3, 3, 3, 2, 1, 1, 1}, // 19
            {4, 3, 3, 3, 3, 2, 2, 1, 1}, // 20
    };

    /** Fresh, unspent spell slots for a class at a given character level. */
    static List<SpellSlot> forClass(String characterClass, int level) {
        List<SpellSlot> slots = new ArrayList<>();
        if (characterClass == null) return slots;

        String c = characterClass.toLowerCase(Locale.ROOT).trim();
        int effectiveLevel;
        if (FULL_CASTERS.contains(c)) {
            effectiveLevel = level;
        } else if (HALF_CASTERS.contains(c) || PACT_CASTERS.contains(c)) {
            effectiveLevel = level / 2; // half progression (round down)
        } else {
            return slots; // martial — no slots
        }

        if (effectiveLevel < 1) return slots;
        int idx = Math.min(effectiveLevel, FULL.length) - 1;
        int[] row = FULL[idx];
        for (int i = 0; i < row.length; i++) {
            if (row[i] > 0) {
                slots.add(new SpellSlot(i + 1, row[i], 0));
            }
        }
        return slots;
    }
}
