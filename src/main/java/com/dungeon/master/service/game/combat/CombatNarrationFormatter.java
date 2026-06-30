package com.dungeon.master.service.game.combat;

import com.dungeon.master.model.dto.ActiveCondition;
import com.dungeon.master.model.dto.DiceRollResult;
import com.dungeon.master.model.dto.PlayerRuntimeStateDto;
import com.dungeon.master.model.dto.RollSummary;
import com.dungeon.master.model.entity.Enemy;
import com.dungeon.master.model.enums.ItemKind;
import com.dungeon.master.service.ai.EnemyTacticsService.EnemyIntent;
import com.dungeon.master.service.game.ConditionRules;
import com.dungeon.master.service.game.PlayerStateService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Pure builders for the mechanical narration "beat" lines surfaced during combat. No state and
 * no side effects — every method maps already-resolved values to a single sentence.
 */
public final class CombatNarrationFormatter {

    private CombatNarrationFormatter() {}

    /** One mechanical attack line: "X hits Y (attack 18 vs AC 15) for 7 damage — a solid hit (Y now 4/11 HP)." */
    public static String describeAttack(String attacker, String target, DiceRollResult atk,
                                        int targetAc, boolean hit, RollSummary dmg,
                                        int targetHp, int targetMax, boolean defeated) {
        if (!hit) {
            String why = atk.fumble() ? " (a fumble)" : "";
            return attacker + " attacks " + target + " but misses" + why
                    + " (attack " + atk.total() + " vs AC " + targetAc + ").";
        }
        String crit = atk.crit() ? "a critical hit — " : "";
        String head = attacker + " hits " + target + " (" + crit + "attack " + atk.total()
                + " vs AC " + targetAc + ") for " + dmg.total() + " damage"
                + magnitudeTag(dmg.total(), targetMax, atk.crit());
        if (defeated) {
            return head + ", defeating " + target + ".";
        }
        return head + " (" + target + " now at " + targetHp + "/" + targetMax + " HP).";
    }

    /**
     * A qualitative damage cue so narration scales drama by how hard the blow landed — a big
     * fraction of the target's max HP (or a crit) reads as devastating; a sliver as glancing.
     */
    private static String magnitudeTag(int damage, int targetMax, boolean crit) {
        if (targetMax <= 0) return "";
        double frac = (double) damage / targetMax;
        if (crit || frac >= 0.40) return " — a devastating blow";
        if (frac >= 0.20) return " — a solid hit";
        if (frac <= 0.12) return " — a glancing hit";
        return "";
    }

    public static String describeItemUse(String actor, PlayerStateService.ItemUseResult r) {
        if (r.kind() == ItemKind.POTION_HEALING && r.healed() > 0) {
            return actor + " uses " + r.itemName() + ", recovering " + r.healed()
                    + " HP (now " + r.state().currentHp() + "/" + r.state().maxHp() + " HP).";
        }
        return actor + " uses " + r.itemName() + ".";
    }

    /** One narration line for a death save, e.g. "Aria steadies — death save 14: success (2/3)." */
    public static String describeDeathSave(String name, DiceRollResult roll,
                                           PlayerStateService.DeathSaveResult result) {
        PlayerRuntimeStateDto s = result.state();
        return switch (result.outcome()) {
            case REVIVED -> name + " gasps back to life — death save " + roll.total()
                    + " (a natural 20!), conscious again at 1 HP.";
            case STABILIZED -> name + " stabilizes — death save " + roll.total()
                    + ": success (" + s.deathSaveSuccesses() + "/3), now holding on at 0 HP.";
            case DIED -> name + " slips away — death save " + roll.total()
                    + ": failure (" + s.deathSaveFailures() + "/3).";
            case SUCCESS -> name + " steadies — death save " + roll.total()
                    + ": success (" + s.deathSaveSuccesses() + "/3).";
            case FAILURE -> name + " falters — death save " + roll.total()
                    + ": failure (" + s.deathSaveFailures() + "/3).";
        };
    }

    /** One narration line for an enemy's intent-driven reposition. */
    public static String enemyMoveLine(Enemy enemy, EnemyIntent intent, String targetName) {
        return switch (intent) {
            case FLEE -> enemy.getName() + " retreats from the party.";
            case KITE_RANGED -> enemy.getName() + " repositions to keep " + targetName + " at range.";
            case HOLD -> enemy.getName() + " holds its ground.";
            default -> enemy.getName() + " advances toward " + targetName + ".";
        };
    }

    public static String roster(List<Enemy> enemies) {
        return enemies.stream().map(Enemy::getName).collect(Collectors.joining(", "));
    }

    /** First incapacitating condition name on the list, for the "X is <…> and can't act" beat. */
    public static String incapacitatingLabel(List<ActiveCondition> conds) {
        for (ActiveCondition c : conds) {
            if (ConditionRules.incapacitated(List.of(c))) {
                return c.name();
            }
        }
        return "incapacitated";
    }
}
