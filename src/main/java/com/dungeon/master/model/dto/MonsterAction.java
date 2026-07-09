package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.MonsterActionKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One legendary or lair action option, hand-authored in {@code resources/dnd5e/monster-actions.json}
 * and loaded by {@code MonsterCatalog}. Rides on {@link com.dungeon.master.model.entity.Enemy} as
 * JSONB so a spawned boss keeps its options for the life of the encounter.
 *
 * <p>Legendary actions are spent from a per-round budget at the end of each hero's turn; lair
 * actions fire once per round on initiative count 20. Both resolve through the same three shapes:
 *
 * <ul>
 *   <li>{@link MonsterActionKind#ATTACK} — {@code attackName} names an entry in the monster's own
 *       {@link MonsterAttack} list, so the existing swing resolution (to-hit vs AC, damage,
 *       resistances, concentration checks) is reused verbatim.</li>
 *   <li>{@link MonsterActionKind#SAVE} — every conscious hero within {@code radiusFeet} of the
 *       monster rolls {@code saveAbility} against {@code saveDc}. A failure takes
 *       {@code damageDice} of {@code damageType} and gains {@code condition}; a success takes half
 *       when {@code halfOnSave} and no condition.</li>
 *   <li>{@link MonsterActionKind#NARRATIVE} — flavour only ({@code description}).</li>
 * </ul>
 *
 * @param name            display name, e.g. "Wing Attack"
 * @param cost            legendary-action points spent (1–3); ignored for lair actions
 * @param kind            how the engine resolves it
 * @param attackName      ATTACK: the {@link MonsterAttack#name()} to swing with
 * @param saveAbility     SAVE: STR/DEX/CON/INT/WIS/CHA
 * @param saveDc          SAVE: the DC heroes roll against
 * @param damageDice      SAVE: damage on a failed save, e.g. "2d6+9"; null for no damage
 * @param damageType      SAVE: e.g. "Bludgeoning"; null when there is no damage
 * @param halfOnSave      SAVE: a successful save takes half damage rather than none
 * @param condition       SAVE: {@code ConditionRules} key applied on a failure, e.g. "prone"
 * @param conditionRounds SAVE: rounds the condition lasts; null means until removed
 * @param radiusFeet      SAVE: emanation radius around the monster; null targets one hero
 * @param description     narration fallback, also used for the combat beat line
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MonsterAction(
        String name,
        int cost,
        MonsterActionKind kind,
        String attackName,
        String saveAbility,
        Integer saveDc,
        String damageDice,
        String damageType,
        boolean halfOnSave,
        String condition,
        Integer conditionRounds,
        Integer radiusFeet,
        String description
) {
    /** Legendary-action points this option costs, floored at 1 so a malformed entry can't be free. */
    public int pointCost() {
        return Math.max(1, cost);
    }
}
