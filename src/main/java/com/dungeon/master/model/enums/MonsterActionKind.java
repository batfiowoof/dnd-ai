package com.dungeon.master.model.enums;

/**
 * How the engine resolves a {@link com.dungeon.master.model.dto.MonsterAction} — a legendary
 * or lair action taken outside the monster's own turn.
 *
 * <p>{@code ATTACK} reuses one of the monster's existing stat-block attacks (an attack roll
 * against a single hero's AC); {@code SAVE} forces every hero in the area to roll a saving
 * throw; {@code NARRATIVE} has no mechanical effect and is only described.
 */
public enum MonsterActionKind {
    ATTACK,
    SAVE,
    NARRATIVE
}
