package com.dungeon.master.model.enums;

/**
 * What a spell mechanically does in combat, derived from its SRD prose by
 * {@code tools/extract-srd.py}. Drives how {@code CombatService.playerCastSpell}
 * resolves the cast. {@code UTILITY} spells have no engine-resolvable effect and
 * are narrated only.
 */
public enum SpellEffectType {
    DAMAGE,
    HEAL,
    BUFF,
    DEBUFF,
    CONTROL,
    UTILITY
}
