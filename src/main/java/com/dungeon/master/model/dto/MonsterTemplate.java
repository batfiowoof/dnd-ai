package com.dungeon.master.model.dto;

import java.util.List;
import java.util.Map;

/**
 * A monster stat block from the SRD 5.2.1, parsed by {@code tools/extract-srd.py}
 * into {@code resources/dnd5e/monsters.json} and loaded by {@code MonsterCatalog}.
 * Replaces the old hardcoded {@code Bestiary}. Used to instantiate per-encounter
 * {@link com.dungeon.master.model.entity.Enemy} rows.
 *
 * @param multiattack {@code count} attacks of the named attack per turn, or null
 */
public record MonsterTemplate(
        String key,
        String name,
        String size,
        String type,
        Double cr,
        Integer ac,
        Integer hp,
        String hpDice,
        Integer speed,
        int dexMod,
        Map<String, Integer> abilities,
        List<MonsterAttack> attacks,
        Multiattack multiattack
) {
    /** A monster's Multiattack: {@code count} repeats of the named base attack. */
    public record Multiattack(int count, String attack) {}
}
