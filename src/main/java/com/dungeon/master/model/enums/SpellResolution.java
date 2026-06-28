package com.dungeon.master.model.enums;

/**
 * How a spell's hit is decided: a spell attack roll vs the target's AC, a saving
 * throw vs the caster's spell save DC, or automatic (no roll — e.g. Magic Missile
 * and most healing).
 */
public enum SpellResolution {
    SPELL_ATTACK,
    SAVE,
    AUTO
}
