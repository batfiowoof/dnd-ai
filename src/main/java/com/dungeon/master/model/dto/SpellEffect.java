package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.SpellEffectType;
import com.dungeon.master.model.enums.SpellResolution;
import com.dungeon.master.model.enums.SpellTargetType;

/**
 * Machine-readable combat mechanics for one spell, parsed from the SRD prose by
 * {@code tools/extract-srd.py} and loaded by {@code SpellCatalog}. Flattens the
 * {@code combat} block of the structured dataset.
 *
 * @param name          spell display name
 * @param level         0 for cantrips, else 1–9
 * @param effectType    what it does (DAMAGE/HEAL/BUFF/DEBUFF/CONTROL/UTILITY)
 * @param targetType    who it may target (ENEMY/ALLY/SELF/AREA/ANY)
 * @param resolution    how a hit is decided (SPELL_ATTACK/SAVE/AUTO)
 * @param saveAbility   STR/DEX/CON/INT/WIS/CHA when {@code resolution == SAVE}, else null
 * @param damageDice    e.g. "8d6", "1d4+1", or a flat number; null when no damage
 * @param damageType    e.g. "Fire"; null when no damage
 * @param healDice      e.g. "2d8"; null when no heal
 * @param addCastingMod add the caster's spellcasting modifier to the heal/damage
 * @param halfOnSave    target takes half damage on a successful save
 * @param perSlotAbove  extra dice per slot level above base (leveled-spell scaling), else null
 * @param cantripDie    extra die added at character levels 5/11/17 (cantrip scaling), else null
 * @param aoeShape      "sphere"/"cube"/"cone"/"line"/"emanation"/"cylinder", or null
 * @param aoeSize       AoE size in feet, or 0
 * @param maxTargets    max selectable targets; null means "all in the area"
 * @param projectiles   number of separate darts/rays/beams (Magic Missile = 3), else 1
 * @param condition     condition imposed by a CONTROL/DEBUFF spell (e.g. "frightened"), else null
 * @param concentration whether the spell requires concentration
 * @param parsed        true when the engine can resolve it mechanically (else narrate only)
 * @param castingTime   the spell's casting time, e.g. "Action", "Bonus Action", "Reaction"
 * @param range         the spell's range, e.g. "Touch", "60 feet", "Self" (drives melee spell attacks)
 */
public record SpellEffect(
        String name,
        int level,
        SpellEffectType effectType,
        SpellTargetType targetType,
        SpellResolution resolution,
        String saveAbility,
        String damageDice,
        String damageType,
        String healDice,
        boolean addCastingMod,
        boolean halfOnSave,
        String perSlotAbove,
        String cantripDie,
        String aoeShape,
        int aoeSize,
        Integer maxTargets,
        int projectiles,
        String condition,
        boolean concentration,
        boolean parsed,
        String castingTime,
        String range
) {

    /** True when this spell is cast as a Bonus Action (so it doesn't consume the action). */
    public boolean isBonusAction() {
        return "Bonus Action".equalsIgnoreCase(castingTime);
    }

    /** True for a touch-range spell attack (melee for advantage-vs-prone purposes). */
    public boolean isMeleeRange() {
        return range != null && range.toLowerCase(java.util.Locale.ROOT).startsWith("touch");
    }
}
