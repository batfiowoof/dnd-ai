package com.dungeon.master.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * A single status effect riding on a combatant (player or enemy), stored in the
 * {@code conditions} JSONB column. Unlike the old bare-string model, a condition now
 * knows where it came from and when it ends, so the combat engine can enforce its
 * mechanics, expire it, and break it when the caster's concentration drops.
 *
 * <p>{@code name} is the canonical lowercase key consumed by {@link com.dungeon.master.service.game.ConditionRules}
 * (the 12 SRD conditions plus a few spell-specific pseudo-conditions such as
 * {@code faerie-fire}, {@code baned}, {@code blessed}, {@code slowed}, {@code enfeebled}).</p>
 *
 * @param name           canonical lowercase condition key
 * @param sourceCasterId player who applied it (null for non-spell sources)
 * @param sourceSpell    spell name that applied it (for narration / concentration linkage)
 * @param concentration  true when it ends as soon as the caster's concentration ends
 * @param expiresAtRound combat round after which it lapses; {@code null} = until removed/concentration drops
 * @param saveAbility    if set, the victim re-rolls this save at the end of its turn to shake it off
 * @param saveDc         DC for the end-of-turn save (only meaningful with {@code saveAbility})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActiveCondition(
        String name,
        UUID sourceCasterId,
        String sourceSpell,
        boolean concentration,
        Integer expiresAtRound,
        String saveAbility,
        Integer saveDc
) {

    /** A plain condition with no source/duration (e.g. seeded or DM-applied). */
    public static ActiveCondition of(String name) {
        return new ActiveCondition(name, null, null, false, null, null, null);
    }

    /** A spell-applied condition; duration handled separately via the with* helpers. */
    public static ActiveCondition fromSpell(String name, UUID casterId, String spell, boolean concentration) {
        return new ActiveCondition(name, casterId, spell, concentration, null, null, null);
    }

    /** Copy with a fixed expiry round (e.g. Command — until the end of the target's next turn). */
    public ActiveCondition expiringAt(Integer round) {
        return new ActiveCondition(name, sourceCasterId, sourceSpell, concentration, round, saveAbility, saveDc);
    }

    /** Copy with a "save ends" clause re-rolled at the victim's end of turn. */
    public ActiveCondition savingEnds(String ability, Integer dc) {
        return new ActiveCondition(name, sourceCasterId, sourceSpell, concentration, expiresAtRound, ability, dc);
    }
}
