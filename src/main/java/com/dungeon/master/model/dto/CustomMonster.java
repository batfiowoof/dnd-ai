package com.dungeon.master.model.dto;

import java.util.List;
import java.util.Map;

/**
 * An authored, homebrew monster stat block carried by a {@link com.dungeon.master.model.entity.World}.
 * Mirrors {@link MonsterTemplate} field-for-field so it converts 1:1 via {@link #toTemplate()} — once
 * a world is started as a session, its custom monsters overlay the SRD {@code MonsterCatalog} and are
 * instantiated into combat {@link com.dungeon.master.model.entity.Enemy} rows exactly like SRD
 * creatures, so all combat math (HP/difficulty scaling, multiattack, saves, initiative) is reused.
 *
 * <p>Keys are namespaced with a {@code CUSTOM_} prefix and upper-cased so a homebrew creature can
 * never collide with an SRD catalogue key.
 */
public record CustomMonster(
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
        MonsterTemplate.Multiattack multiattack
) {
    /**
     * Convert to the engine's {@link MonsterTemplate} so the combat path treats it like any SRD block.
     * Homebrew creatures carry no boss mechanics — legendary and lair actions are authored only for
     * the curated SRD set in {@code resources/dnd5e/monster-actions.json}.
     */
    public MonsterTemplate toTemplate() {
        return new MonsterTemplate(key, name, size, type, cr, ac, hp, hpDice, speed, dexMod,
                abilities, attacks, multiattack, 0, List.of(), List.of(), 0);
    }
}
