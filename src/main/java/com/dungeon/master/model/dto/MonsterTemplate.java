package com.dungeon.master.model.dto;

import java.util.List;
import java.util.Map;

/**
 * A monster stat block from the SRD 5.2.1, parsed by {@code tools/extract-srd.py}
 * into {@code resources/dnd5e/monsters.json} and loaded by {@code MonsterCatalog}.
 * Replaces the old hardcoded {@code Bestiary}. Used to instantiate per-encounter
 * {@link com.dungeon.master.model.entity.Enemy} rows.
 *
 * <p>Boss mechanics ({@code legendaryActions}, {@code lairActions}, {@code legendaryResistances})
 * are not in the generated dataset — the SRD prose is stat-free for these — so they come from the
 * hand-authored {@code resources/dnd5e/monster-actions.json} overlay, merged by key.
 *
 * @param multiattack          {@code count} attacks of the named attack per turn, or null
 * @param legendaryActionMax   legendary-action points regained each round; 0 = not a legendary creature
 * @param legendaryActions     options spendable at the end of a hero's turn (empty when none)
 * @param lairActions          options fired on initiative count 20 when fought in the lair (empty when none)
 * @param legendaryResistances times per encounter the monster may turn a failed save into a success
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
        Multiattack multiattack,
        int legendaryActionMax,
        List<MonsterAction> legendaryActions,
        List<MonsterAction> lairActions,
        int legendaryResistances
) {
    /** A monster's Multiattack: {@code count} repeats of the named base attack. */
    public record Multiattack(int count, String attack) {}

    /** True when this monster takes legendary actions between the heroes' turns. */
    public boolean isLegendary() {
        return legendaryActionMax > 0 && legendaryActions != null && !legendaryActions.isEmpty();
    }

    /** True when this monster has authored lair actions the host may enable for an encounter. */
    public boolean hasLair() {
        return lairActions != null && !lairActions.isEmpty();
    }
}
