package com.dungeon.master.model.dto;

import com.dungeon.master.model.enums.CombatantKind;

import java.util.UUID;

/**
 * One composite combat action (attack roll → hit/miss vs AC → damage). Carried as
 * a single event so the modal can sequence the whole animation without competing
 * DICE_ROLL broadcasts. Used for both enemy and player attacks.
 */
public record EnemyActionEvent(
        String type,
        UUID sessionId,
        CombatantKind attackerKind,
        String attackerName,
        CombatantKind targetKind,
        String targetName,
        RollSummary attackRoll,
        int vsAc,
        boolean hit,
        RollSummary damageRoll,
        int targetCurrentHp,
        int targetMaxHp,
        boolean targetDefeated,
        CombatStateDto combat
) {
    public static final String TYPE = "ENEMY_ACTION";
}
