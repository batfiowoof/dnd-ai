package com.dungeon.master.model.dto;

/**
 * One weapon-style attack on a monster stat block (attack-roll vs AC then damage).
 * Parsed from the SRD by {@code tools/extract-srd.py}; persisted on {@link
 * com.dungeon.master.model.entity.Enemy} as JSON so multiattack monsters keep all
 * their attacks.
 *
 * @param name       e.g. "Scimitar"
 * @param kind       "MELEE" or "RANGED"
 * @param toHit      attack bonus (added to 1d20)
 * @param reach      melee reach in feet, or null
 * @param range      ranged normal range in feet, or null
 * @param damageDice e.g. "2d6+5", or a flat number for tiny creatures ("1")
 * @param damageType e.g. "Slashing"
 */
public record MonsterAttack(
        String name,
        String kind,
        int toHit,
        Integer reach,
        Integer range,
        String damageDice,
        String damageType
) {
}
